package com.shijiangtai.m3classroom.application.port;

import java.time.Duration;

/**
 * Application port for delayed work (hand-raise generation, hand-window timeout, 30 s grace).
 * Tests inject a manual scheduler; production uses a {@link java.util.concurrent.ScheduledExecutorService}.
 */
public interface SchedulerPort {

    Cancellable schedule(Runnable task, Duration delay);

    /** Handle used to cancel a scheduled task that is no longer valid. */
    interface Cancellable {
        void cancel();
    }
}
