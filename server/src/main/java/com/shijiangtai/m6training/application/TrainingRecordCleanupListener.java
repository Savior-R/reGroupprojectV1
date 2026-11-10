package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.event.TrainingRecordCleanupRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Runs physical cleanup only after the marking transaction commits; failures are retried. */
@Component
public class TrainingRecordCleanupListener {
    private static final Logger log =
            LoggerFactory.getLogger(TrainingRecordCleanupListener.class);

    private final TrainingRecordCleanupExecutor cleanupExecutor;

    public TrainingRecordCleanupListener(TrainingRecordCleanupExecutor cleanupExecutor) {
        this.cleanupExecutor = cleanupExecutor;
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCleanupRequested(TrainingRecordCleanupRequestedEvent event) {
        try {
            cleanupExecutor.cleanupRecord(event.recordId());
        } catch (RuntimeException exception) {
            log.warn("Training record cleanup deferred for {}", event.recordId(), exception);
        }
    }
}
