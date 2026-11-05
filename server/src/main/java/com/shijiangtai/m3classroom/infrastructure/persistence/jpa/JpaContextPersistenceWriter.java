package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.application.port.ContextPersistenceWriter;
import com.shijiangtai.m3classroom.application.port.PersistenceFlushException;
import com.shijiangtai.m3classroom.application.port.SessionBatchStore;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.shared.domain.SessionId;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Asynchronous {@link ContextPersistenceWriter}: one ordered queue per session, drained by a
 * shared executor every 1 s, at 100 items, or at 64 KB, whichever comes first (05A §9.2).
 *
 * <p>Critical events call {@link #flush(SessionId)} synchronously; it retries three times with
 * 50/100/200 ms backoff and re-queues the failed batch at the head before throwing
 * {@link PersistenceFlushException}. Asynchronous failures are logged and leave the same batch
 * queued for the next tick, so a transient database error never blocks the actor.
 *
 * <p>Deadlock rule: the flush thread only takes a queue's {@link SessionQueue#flushLock} and
 * touches the queue itself; it must never call into a {@link
 * com.shijiangtai.m3classroom.application.SessionActor} or acquire an actor monitor. Actor
 * threads may therefore wait on the flush lock without a lock cycle.
 *
 * <p>The queue is bounded at 10 000 items: enqueueing past the limit first flushes synchronously,
 * and still being full throws a queue-full {@link PersistenceFlushException} so the caller can
 * treat it like any other flush failure.
 */
@Repository
public class JpaContextPersistenceWriter implements ContextPersistenceWriter {

    private static final Logger LOG = LoggerFactory.getLogger(JpaContextPersistenceWriter.class);

    private static final int BATCH_ITEMS = 100;
    private static final int BATCH_BYTES = 64 * 1024;
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int MAX_DRAIN_ITEMS = 500;
    /** One initial attempt plus the three retries documented in 05A §9.3. */
    private static final int MAX_ATTEMPTS = 4;
    private static final long FLUSH_INTERVAL_MS = 1_000L;
    private static final long[] PRODUCTION_RETRY_BACKOFF_MS = {50L, 100L, 200L};
    private static final long[] NO_RETRY_BACKOFF_MS = {0L, 0L, 0L, 0L};
    private static final int FLUSH_THREADS = 2;

    private final SessionBatchStore store;
    private final Executor flushExecutor;
    private final ScheduledExecutorService scheduler;
    private final long[] retryBackoffMs;
    private final boolean ownsThreads;
    private final ConcurrentHashMap<SessionId, SessionQueue> queues =
            new ConcurrentHashMap<>();

    @Autowired
    public JpaContextPersistenceWriter(SessionBatchStore store) {
        this(
                store,
                Executors.newFixedThreadPool(
                        FLUSH_THREADS, daemonThreadFactory("m3-context-flush")),
                Executors.newSingleThreadScheduledExecutor(
                        daemonThreadFactory("m3-context-flush-timer")),
                PRODUCTION_RETRY_BACKOFF_MS,
                true);
    }

    /**
     * Test seam: runs flushes on the supplied executor, starts no 1 s scheduler and uses zero
     * retry backoff so failure paths stay fast. Production wiring uses the constructor above.
     */
    JpaContextPersistenceWriter(SessionBatchStore store, Executor flushExecutor) {
        this(store, flushExecutor, null, NO_RETRY_BACKOFF_MS, false);
    }

    private JpaContextPersistenceWriter(
            SessionBatchStore store,
            Executor flushExecutor,
            ScheduledExecutorService scheduler,
            long[] retryBackoffMs,
            boolean ownsThreads) {
        this.store = Objects.requireNonNull(store, "store");
        this.flushExecutor = Objects.requireNonNull(flushExecutor, "flushExecutor");
        this.scheduler = scheduler;
        this.retryBackoffMs = retryBackoffMs.clone();
        this.ownsThreads = ownsThreads;
    }

    @PostConstruct
    void startScheduler() {
        if (scheduler != null) {
            scheduler.scheduleWithFixedDelay(
                    this::flushAll, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        // Best-effort synchronous drain: Java-restart recovery is out of scope, so a final
        // failure only loses the tail, never blocks shutdown.
        for (SessionId id : queues.keySet()) {
            try {
                flush(id);
            } catch (RuntimeException exception) {
                LOG.warn("Failed to flush pending writes for session {} during shutdown",
                        id.value(), exception);
            }
        }
        queues.clear();
        if (ownsThreads && flushExecutor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }

    @Override
    public void appendEvent(SessionEvent event) {
        Objects.requireNonNull(event, "event");
        enqueue(event.sessionId(), new EventItem(event, estimateBytes(event)));
    }

    @Override
    public void saveText(SessionText text) {
        Objects.requireNonNull(text, "text");
        enqueue(text.sessionId(), new TextItem(text, estimateBytes(text)));
    }

    /** Best-effort drain then forget the queue; a failure is logged, never propagated. */
    @Override
    public void close(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        try {
            flush(sessionId);
        } catch (RuntimeException exception) {
            LOG.warn("Failed to flush pending writes for session {} on close",
                    sessionId.value(), exception);
        } finally {
            queues.remove(sessionId);
        }
    }

    @Override
    public void flush(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        SessionQueue queue = queues.get(sessionId);
        if (queue == null) {
            return;
        }
        queue.flushLock.lock();
        try {
            while (true) {
                List<Item> batch = drain(queue);
                if (batch.isEmpty()) {
                    return;
                }
                writeWithRetry(sessionId, queue, batch);
            }
        } finally {
            queue.flushLock.unlock();
        }
    }

    private void enqueue(SessionId sessionId, Item item) {
        SessionQueue queue = queues.computeIfAbsent(sessionId, key -> new SessionQueue());
        boolean thresholdReached;
        boolean overCapacity;
        synchronized (queue) {
            queue.pending.addLast(item);
            queue.bytes += item.bytes();
            int size = queue.pending.size();
            overCapacity = size >= QUEUE_CAPACITY;
            thresholdReached = size >= BATCH_ITEMS || queue.bytes >= BATCH_BYTES;
        }
        if (overCapacity) {
            // Synchronous overflow flush keeps the queue bounded; a failed flush propagates so
            // the actor can treat it exactly like a critical flush failure.
            flush(sessionId);
            synchronized (queue) {
                if (queue.pending.size() >= QUEUE_CAPACITY) {
                    throw new PersistenceFlushException(sessionId, "持久化队列已满");
                }
            }
        } else if (thresholdReached) {
            flushAsync(sessionId);
        }
    }

    private void flushAsync(SessionId sessionId) {
        SessionQueue queue = queues.get(sessionId);
        // Coalesce scheduler + size triggers into at most one queued/running task per session.
        // A forced flush(SessionId) bypasses this guard and drains under the flush lock.
        if (queue == null || !queue.flushScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            flushExecutor.execute(() -> {
                try {
                    flush(sessionId);
                } catch (PersistenceFlushException exception) {
                    // flush() already requeued the batch: the next tick retries it.
                    LOG.warn("Async persistence flush failed for session {}; batch kept for retry",
                            sessionId.value(), exception);
                } catch (RuntimeException exception) {
                    LOG.error("Unexpected failure in async persistence flush for session {}",
                            sessionId.value(), exception);
                } finally {
                    queue.flushScheduled.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            queue.flushScheduled.set(false);
            // Executor shut down (application stop): leave the batch for the next tick or drop it
            // with the process; never fail the actor's append.
            LOG.warn("Persistence flush executor rejected session {}", sessionId.value(),
                    exception);
        }
    }

    /** Submits a drain for every session that still has pending writes. */
    private void flushAll() {
        for (SessionId sessionId : queues.keySet()) {
            SessionQueue queue = queues.get(sessionId);
            if (queue == null) {
                continue;
            }
            boolean pending;
            synchronized (queue) {
                pending = !queue.pending.isEmpty();
            }
            if (pending) {
                flushAsync(sessionId);
            }
        }
    }

    private static List<Item> drain(SessionQueue queue) {
        synchronized (queue) {
            int count = Math.min(queue.pending.size(), MAX_DRAIN_ITEMS);
            if (count == 0) {
                return List.of();
            }
            List<Item> batch = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Item item = queue.pending.pollFirst();
                queue.bytes -= item.bytes();
                batch.add(item);
            }
            if (queue.bytes < 0) {
                queue.bytes = 0;
            }
            return batch;
        }
    }

    private void writeWithRetry(SessionId sessionId, SessionQueue queue, List<Item> batch) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                write(batch);
                return;
            } catch (RuntimeException exception) {
                failure = exception;
                LOG.warn("Persistence batch write failed for session {} (attempt {}/{}): {}",
                        sessionId.value(), attempt, MAX_ATTEMPTS, exception.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepBeforeRetry(attempt);
                }
            }
        }
        // Preserve event/text order for the retry: put the drained batch back at the head.
        requeue(queue, batch);
        throw new PersistenceFlushException(sessionId, "持久化 flush 失败", failure);
    }

    private void write(List<Item> batch) {
        List<SessionEvent> events = new ArrayList<>(batch.size());
        List<SessionText> texts = new ArrayList<>(batch.size());
        for (Item item : batch) {
            switch (item) {
                case EventItem eventItem -> events.add(eventItem.event());
                case TextItem textItem -> texts.add(textItem.text());
            }
        }
        store.write(events, texts);
    }

    private static void requeue(SessionQueue queue, List<Item> batch) {
        synchronized (queue) {
            for (int i = batch.size() - 1; i >= 0; i--) {
                Item item = batch.get(i);
                queue.pending.addFirst(item);
                queue.bytes += item.bytes();
            }
        }
    }

    private void sleepBeforeRetry(int attempt) {
        long delayMs = retryBackoffMs[Math.min(attempt - 1, retryBackoffMs.length - 1)];
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static int estimateBytes(SessionEvent event) {
        int bytes = 128 + event.type().length();
        for (Map.Entry<String, Object> entry : event.payload().entrySet()) {
            bytes += entry.getKey().length();
            Object value = entry.getValue();
            bytes += value == null ? 4 : String.valueOf(value).length();
        }
        return bytes;
    }

    private static int estimateBytes(SessionText text) {
        return 128 + text.text().length();
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private sealed interface Item permits EventItem, TextItem {
        int bytes();
    }

    private record EventItem(SessionEvent event, int bytes) implements Item {
    }

    private record TextItem(SessionText text, int bytes) implements Item {
    }

    private static final class SessionQueue {
        private final ArrayDeque<Item> pending = new ArrayDeque<>();
        private final ReentrantLock flushLock = new ReentrantLock();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();
        private int bytes;
    }
}
