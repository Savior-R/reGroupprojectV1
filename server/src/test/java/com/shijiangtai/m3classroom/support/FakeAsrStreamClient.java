package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.AsrChunk;
import com.shijiangtai.m3classroom.application.port.AsrRequest;
import com.shijiangtai.m3classroom.application.port.AsrSession;
import com.shijiangtai.m3classroom.application.port.AsrStreamClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Controllable streaming-ASR port for integration tests.
 *
 * <p>Tests emit partial/final chunks through {@link FakeAsrSession}; {@link #failOnOpen} makes the
 * next {@link #open(AsrRequest)} throw so the handler's degraded path can be exercised. A session
 * can be configured with {@link FakeAsrSession#emitFinalAfterFinish} to mimic a service that sends
 * one last final chunk shortly after {@code finish} and then closes.
 */
public final class FakeAsrStreamClient implements AsrStreamClient {

    private final AtomicReference<RuntimeException> openFailure = new AtomicReference<>();
    private final AtomicReference<FakeAsrSession> lastSession = new AtomicReference<>();
    private final List<AsrRequest> requests = new CopyOnWriteArrayList<>();

    public void reset() {
        openFailure.set(null);
        lastSession.set(null);
        requests.clear();
    }

    public void failOnOpen(RuntimeException failure) {
        openFailure.set(Objects.requireNonNull(failure, "failure"));
    }

    public int openCount() {
        return requests.size();
    }

    public List<AsrRequest> requests() {
        return List.copyOf(requests);
    }

    public AsrRequest lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    public FakeAsrSession lastSession() {
        return lastSession.get();
    }

    @Override
    public AsrSession open(AsrRequest request) {
        Objects.requireNonNull(request, "request");
        requests.add(request);
        RuntimeException failure = openFailure.get();
        if (failure != null) {
            throw failure;
        }
        FakeAsrSession session = new FakeAsrSession();
        lastSession.set(session);
        return session;
    }

    /** One fake ASR session with directly callable chunk/error/closed callbacks. */
    public static final class FakeAsrSession implements AsrSession {

        private final List<byte[]> sentFrames = new CopyOnWriteArrayList<>();
        private final List<Throwable> callbackErrors = new CopyOnWriteArrayList<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Consumer<AsrChunk> chunkListener = chunk -> { };
        private volatile Runnable closedListener = () -> { };
        private volatile Consumer<RuntimeException> errorListener = error -> { };
        private volatile AsrChunk delayedFinal;
        private volatile Duration delayedFinalDelay = Duration.ZERO;
        private volatile ScheduledExecutorService finishExecutor;

        public void emitPartial(String text, Long beginMs, Long endMs, BigDecimal confidence) {
            emit(new AsrChunk(text, false, beginMs, endMs, confidence));
        }

        public void emitFinal(String text, Long beginMs, Long endMs, BigDecimal confidence) {
            emit(new AsrChunk(text, true, beginMs, endMs, confidence));
        }

        public void emit(AsrChunk chunk) {
            try {
                chunkListener.accept(chunk);
            } catch (Throwable error) {
                callbackErrors.add(error);
            }
        }

        public void emitError(RuntimeException error) {
            try {
                errorListener.accept(error);
            } catch (Throwable callbackError) {
                callbackErrors.add(callbackError);
            }
        }

        public void emitClosed() {
            try {
                closedListener.run();
            } catch (Throwable error) {
                callbackErrors.add(error);
            }
        }

        /**
         * After {@link #finish()} waits {@code delay}, emits {@code chunk} and then invokes the
         * closed callback (mimics {@code chunk(final)} followed by {@code done}).
         */
        public void emitFinalAfterFinish(AsrChunk chunk, Duration delay) {
            this.delayedFinal = Objects.requireNonNull(chunk, "chunk");
            this.delayedFinalDelay = Objects.requireNonNull(delay, "delay");
        }

        public List<byte[]> sentFrames() {
            return List.copyOf(sentFrames);
        }

        public List<Throwable> callbackErrors() {
            return List.copyOf(callbackErrors);
        }

        public boolean finished() {
            return finished.get();
        }

        public boolean closed() {
            return closed.get();
        }

        @Override
        public void send(byte[] pcm) {
            sentFrames.add(pcm);
        }

        @Override
        public void finish() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            AsrChunk chunk = delayedFinal;
            if (chunk == null) {
                return;
            }
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fake-asr-drain");
                thread.setDaemon(true);
                return thread;
            });
            finishExecutor = executor;
            executor.schedule(() -> {
                try {
                    emit(chunk);
                    emitClosed();
                } finally {
                    executor.shutdown();
                }
            }, delayedFinalDelay.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void onChunk(Consumer<AsrChunk> listener) {
            this.chunkListener = Objects.requireNonNull(listener, "listener");
        }

        @Override
        public void onClosed(Runnable listener) {
            this.closedListener = Objects.requireNonNull(listener, "listener");
        }

        @Override
        public void onError(Consumer<RuntimeException> listener) {
            this.errorListener = Objects.requireNonNull(listener, "listener");
        }

        @Override
        public void close() {
            closed.set(true);
            ScheduledExecutorService executor = finishExecutor;
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }
}
