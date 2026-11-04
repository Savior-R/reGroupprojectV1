package com.shijiangtai.m3classroom.infrastructure.time;

import com.shijiangtai.m3classroom.application.port.SchedulerPort;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link SchedulerPort} backed by a single daemon
 * {@link ScheduledExecutorService}.
 *
 * <p>Task 11 reuses the same scheduler for the 1 s tick and the 30 s WebSocket grace timer; a
 * single thread keeps those timers ordered and cheap. The bean's destroy method shuts the pool
 * down.
 */
public final class SystemScheduler implements SchedulerPort, AutoCloseable {

    private final ScheduledExecutorService executor;

    public SystemScheduler() {
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "m3-session-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public Cancellable schedule(Runnable task, Duration delay) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        ScheduledFuture<?> future = executor.schedule(
                task, delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
