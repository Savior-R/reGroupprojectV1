package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.domain.TrainingSessionMetaRepository;
import com.shijiangtai.shared.application.ClockProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * M6 housekeeping without enabling global scheduling (08 §7.3, §13).
 *
 * <ul>
 *   <li>every minute: auto-end in-progress sessions at max duration;</li>
 *   <li>every 5 minutes: template visibility reconciler;</li>
 *   <li>every 10 minutes: expire records / materials and retry physical cleanup.</li>
 * </ul>
 */
@Component
@Profile("!test")
@ConditionalOnProperty(
        name = "m6.maintenance.enabled", havingValue = "true", matchIfMissing = true)
public class TrainingMaintenanceScheduler {
    private static final Logger log =
            LoggerFactory.getLogger(TrainingMaintenanceScheduler.class);

    private final TrainingCleanupService cleanupService;
    private final TrainingTemplateReconciler templateReconciler;
    private final TrainingSessionLifecycle sessionLifecycle;
    private final TrainingSessionMetaRepository sessionMetaRepository;
    private final ClockProvider clock;

    private ScheduledExecutorService executor;

    public TrainingMaintenanceScheduler(
            TrainingCleanupService cleanupService,
            TrainingTemplateReconciler templateReconciler,
            TrainingSessionLifecycle sessionLifecycle,
            TrainingSessionMetaRepository sessionMetaRepository,
            ClockProvider clock) {
        this.cleanupService = cleanupService;
        this.templateReconciler = templateReconciler;
        this.sessionLifecycle = sessionLifecycle;
        this.sessionMetaRepository = sessionMetaRepository;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "m6-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::endExpiredSessions, 1, 1, TimeUnit.MINUTES);
        executor.scheduleWithFixedDelay(
                this::reconcileTemplates, 2, 5, TimeUnit.MINUTES);
        executor.scheduleWithFixedDelay(
                this::cleanup, 5, 10, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void endExpiredSessions() {
        try {
            for (var deadline : sessionMetaRepository.listInProgressPastDeadline(
                    clock.now(), 100)) {
                sessionLifecycle.endAutomatically(deadline.sessionId());
            }
        } catch (RuntimeException exception) {
            log.warn("Training session auto-end scan failed", exception);
        }
    }

    private void reconcileTemplates() {
        try {
            templateReconciler.reconcile();
        } catch (RuntimeException exception) {
            log.warn("Training template reconcile failed", exception);
        }
    }

    private void cleanup() {
        try {
            cleanupService.expireDueRecords();
            cleanupService.cleanupPendingRecords();
            cleanupService.expireUnusedMaterials();
        } catch (RuntimeException exception) {
            log.warn("Training cleanup failed", exception);
        }
    }
}
