package com.shijiangtai.m4eval.application;

import com.shijiangtai.m4eval.application.port.EvaluationRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 60 s finds submissions / training records that never produced an evaluation (06 §4.1).
 * This is the compensation path for a lost trigger event or a failed creation.
 */
@Component
public class EvaluationReconciler {

    private static final Logger log = LoggerFactory.getLogger(EvaluationReconciler.class);
    private static final int BATCH = 100;

    private final EvaluationRepository evaluationRepository;
    private final EvaluationApplicationService applicationService;

    public EvaluationReconciler(
            EvaluationRepository evaluationRepository,
            EvaluationApplicationService applicationService) {
        this.evaluationRepository = Objects.requireNonNull(
                evaluationRepository, "evaluationRepository");
        this.applicationService = Objects.requireNonNull(
                applicationService, "applicationService");
    }

    @Scheduled(fixedDelayString = "${m4.eval.reconciler-interval-ms:60000}",
            initialDelayString = "${m4.eval.reconciler-initial-delay-ms:60000}")
    public void reconcile() {
        List<UUID> submissions = evaluationRepository
                .findSubmissionIdsWithoutEvaluation(BATCH);
        for (UUID submissionId : submissions) {
            try {
                applicationService.getOrCreateForSubmission(submissionId);
            } catch (RuntimeException exception) {
                log.warn("reconcile submission {} failed: {}",
                        submissionId, exception.getMessage());
            }
        }
        List<UUID> trainingRecords = evaluationRepository
                .findTrainingRecordIdsWithoutEvaluation(BATCH);
        for (UUID trainingRecordId : trainingRecords) {
            try {
                applicationService.getOrCreateForTrainingRecord(trainingRecordId);
            } catch (RuntimeException exception) {
                log.warn("reconcile training record {} failed: {}",
                        trainingRecordId, exception.getMessage());
            }
        }
    }
}
