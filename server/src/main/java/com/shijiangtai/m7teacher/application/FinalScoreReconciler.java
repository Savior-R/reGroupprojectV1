package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lost-terminal-event / stale-row reconciliation (07 §6.4).
 *
 * <p>This component deliberately has no surrounding transaction: every
 * {@link FinalScoreService#recompute(UUID)} call goes through the service proxy and therefore
 * runs in its own transaction. One bad row cannot poison the whole reconciliation batch.
 */
@Component
public class FinalScoreReconciler {

    private static final Logger log = LoggerFactory.getLogger(FinalScoreReconciler.class);
    private static final int BATCH = 500;

    private final FinalScoreService finalScoreService;
    private final UpstreamQueryPort upstream;

    public FinalScoreReconciler(
            FinalScoreService finalScoreService, UpstreamQueryPort upstream) {
        this.finalScoreService = finalScoreService;
        this.upstream = upstream;
    }

    @Scheduled(
            fixedDelayString = "${m7.reconcile.fixed-delay-ms:300000}",
            initialDelayString = "${m7.reconcile.initial-delay-ms:60000}")
    public void reconcile() {
        for (UUID submissionId : upstream.findRecentSubmissionIds(BATCH)) {
            try {
                if (finalScoreService.stale(submissionId)) {
                    finalScoreService.recompute(submissionId);
                }
            } catch (RuntimeException exception) {
                log.warn("M7 reconcile failed for submission {}: {}",
                        submissionId, exception.getMessage());
            }
        }
    }
}
