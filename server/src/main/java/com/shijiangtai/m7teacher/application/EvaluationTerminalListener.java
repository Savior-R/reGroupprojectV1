package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.domain.EvaluationTerminalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumes M4's terminal evaluation event (07 §6.4).
 *
 * <p>Kept outside {@link FinalScoreService} so the recompute runs through the service proxy in
 * a fresh transaction, and a failed recompute cannot mark the listener transaction rollback-only.
 */
@Component
public class EvaluationTerminalListener {

    private static final Logger log = LoggerFactory.getLogger(EvaluationTerminalListener.class);

    private final FinalScoreService finalScoreService;

    public EvaluationTerminalListener(FinalScoreService finalScoreService) {
        this.finalScoreService = finalScoreService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTerminal(EvaluationTerminalEvent event) {
        if (event == null || !event.taskSubmission() || event.sourceId() == null) {
            return;
        }
        try {
            finalScoreService.recompute(event.sourceId());
        } catch (RuntimeException exception) {
            log.warn("M7 final-score recompute failed for evaluation {} submission {}: {}",
                    event.evaluationId(), event.sourceId(), exception.getMessage());
        }
    }
}
