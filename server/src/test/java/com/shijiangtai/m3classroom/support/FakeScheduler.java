package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.SchedulerPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic manual scheduler. Tasks run when {@link #advance(Duration)} passes their due
 * time; tasks scheduled by a running task are considered by the same advance call.
 */
public final class FakeScheduler implements SchedulerPort {

    private final List<ScheduledTask> tasks = new ArrayList<>();
    private Duration now = Duration.ZERO;
    private long sequence;

    @Override
    public Cancellable schedule(Runnable task, Duration delay) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        ScheduledTask scheduled = new ScheduledTask(task, now.plus(delay), sequence++);
        tasks.add(scheduled);
        return () -> tasks.remove(scheduled);
    }

    /** Advances virtual time and runs every task whose due time is reached, in due order. */
    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        Duration target = now.plus(duration);
        while (true) {
            ScheduledTask next = tasks.stream()
                    .filter(task -> task.due.compareTo(target) <= 0)
                    .min(Comparator.comparing((ScheduledTask task) -> task.due)
                            .thenComparingLong(task -> task.sequence))
                    .orElse(null);
            if (next == null) {
                break;
            }
            tasks.remove(next);
            if (next.due.compareTo(now) > 0) {
                now = next.due;
            }
            next.task.run();
        }
        now = target;
    }

    public int pendingTasks() {
        return tasks.size();
    }

    private static final class ScheduledTask {
        private final Runnable task;
        private final Duration due;
        private final long sequence;

        private ScheduledTask(Runnable task, Duration due, long sequence) {
            this.task = task;
            this.due = due;
            this.sequence = sequence;
        }
    }
}
