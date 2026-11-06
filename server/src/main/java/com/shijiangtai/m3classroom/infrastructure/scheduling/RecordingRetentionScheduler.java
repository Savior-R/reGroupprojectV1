package com.shijiangtai.m3classroom.infrastructure.scheduling;

import com.shijiangtai.m3classroom.application.RecordingRetentionService;
import com.shijiangtai.m3classroom.application.RecordingRetentionService.RetentionRun;
import com.shijiangtai.shared.application.ClockProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the retention cleanup on a single daemon thread.
 *
 * <p>Excluded from the {@code test} profile so no background thread can delete test data while the
 * suite runs. {@code @EnableScheduling} is deliberately not used: the job owns its executor, which
 * lets the application context shut it down deterministically.
 */
@Component
@Profile("!test")
public class RecordingRetentionScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RecordingRetentionScheduler.class);

    private final RecordingRetentionService service;
    private final ClockProvider clockProvider;
    private final M3RetentionProperties properties;

    private ScheduledExecutorService executor;

    public RecordingRetentionScheduler(
            RecordingRetentionService service,
            ClockProvider clockProvider,
            M3RetentionProperties properties) {
        this.service = Objects.requireNonNull(service, "service");
        this.clockProvider = Objects.requireNonNull(clockProvider, "clockProvider");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @PostConstruct
    public void start() {
        if (!properties.enabled()) {
            log.info("Recording retention scheduler disabled");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "m3-retention-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::runSafely,
                properties.initialDelay().toMillis(),
                properties.interval().toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Recording retention scheduler started: initialDelay={}, interval={}",
                properties.initialDelay(), properties.interval());
    }

    /** Runs one cleanup sweep, swallowing failures so the fixed-delay schedule keeps running. */
    public void runSafely() {
        try {
            RetentionRun run = service.cleanup(clockProvider.now());
            log.info("Retention cleanup run: temporaryDeleted={}, submittedDeleted={}, failed={}",
                    run.temporaryDeleted(), run.submittedDeleted(), run.failed());
        } catch (RuntimeException exception) {
            log.error("Retention cleanup run failed", exception);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
        }
    }
}