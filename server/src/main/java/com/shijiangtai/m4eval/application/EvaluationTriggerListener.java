package com.shijiangtai.m4eval.application;

import com.shijiangtai.m3classroom.domain.submission.SubmissionCreatedEvent;
import com.shijiangtai.m4eval.domain.TrainingRecordSelectedEvent;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Creates an evaluation after a submission or training record is committed (05 §4.1).
 *
 * <p>Creation failures are logged and swallowed: the publisher's transaction has already
 * committed, and {@link EvaluationReconciler} will create the missing evaluation later.
 */
@Component
public class EvaluationTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(EvaluationTriggerListener.class);

    private final EvaluationApplicationService applicationService;

    public EvaluationTriggerListener(EvaluationApplicationService applicationService) {
        this.applicationService = Objects.requireNonNull(
                applicationService, "applicationService");
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSubmissionCreated(SubmissionCreatedEvent event) {
        try {
            applicationService.getOrCreateForSubmission(event.submissionId());
        } catch (RuntimeException exception) {
            log.error("failed to create evaluation for submission {}: {}",
                    event.submissionId(), exception.getMessage(), exception);
        }
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTrainingRecordSelected(TrainingRecordSelectedEvent event) {
        try {
            applicationService.getOrCreateForTrainingRecord(event.trainingRecordId());
        } catch (RuntimeException exception) {
            log.error("failed to create evaluation for training record {}: {}",
                    event.trainingRecordId(), exception.getMessage(), exception);
        }
    }
}
