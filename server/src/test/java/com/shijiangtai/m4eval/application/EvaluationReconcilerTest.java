package com.shijiangtai.m4eval.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shijiangtai.m4eval.application.port.EvaluationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Compensation scan for lost trigger events (06 §4.1, M4-T11). */
class EvaluationReconcilerTest {

    private static final UUID SUBMISSION_A =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUBMISSION_B =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TRAINING_A =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private EvaluationRepository evaluationRepository;
    private EvaluationApplicationService applicationService;
    private EvaluationReconciler reconciler;

    @BeforeEach
    void setUp() {
        evaluationRepository = mock(EvaluationRepository.class);
        applicationService = mock(EvaluationApplicationService.class);
        reconciler = new EvaluationReconciler(evaluationRepository, applicationService);
    }

    @Test
    void scansBothSourcesAndCreatesTheMissingEvaluations() {
        when(evaluationRepository.findSubmissionIdsWithoutEvaluation(100))
                .thenReturn(List.of(SUBMISSION_A, SUBMISSION_B));
        when(evaluationRepository.findTrainingRecordIdsWithoutEvaluation(100))
                .thenReturn(List.of(TRAINING_A));

        reconciler.reconcile();

        verify(applicationService).getOrCreateForSubmission(SUBMISSION_A);
        verify(applicationService).getOrCreateForSubmission(SUBMISSION_B);
        verify(applicationService).getOrCreateForTrainingRecord(TRAINING_A);
    }

    @Test
    void oneFailedSubmissionDoesNotStopTheRestOfTheBatch() {
        when(evaluationRepository.findSubmissionIdsWithoutEvaluation(100))
                .thenReturn(List.of(SUBMISSION_A, SUBMISSION_B));
        when(evaluationRepository.findTrainingRecordIdsWithoutEvaluation(100))
                .thenReturn(List.of(TRAINING_A));
        when(applicationService.getOrCreateForSubmission(SUBMISSION_A))
                .thenThrow(new IllegalStateException("source deleted"));

        assertThatCode(reconciler::reconcile).doesNotThrowAnyException();

        verify(applicationService).getOrCreateForSubmission(SUBMISSION_B);
        verify(applicationService).getOrCreateForTrainingRecord(TRAINING_A);
    }

    @Test
    void aFailedTrainingRecordDoesNotStopSubmissionScanning() {
        when(evaluationRepository.findSubmissionIdsWithoutEvaluation(100))
                .thenReturn(List.of(SUBMISSION_A));
        when(evaluationRepository.findTrainingRecordIdsWithoutEvaluation(100))
                .thenReturn(List.of(TRAINING_A));
        when(applicationService.getOrCreateForTrainingRecord(TRAINING_A))
                .thenThrow(new IllegalStateException("expired"));

        assertThatCode(reconciler::reconcile).doesNotThrowAnyException();

        verify(applicationService).getOrCreateForSubmission(SUBMISSION_A);
    }

    @Test
    void emptyBatchesCallNoTrigger() {
        when(evaluationRepository.findSubmissionIdsWithoutEvaluation(100))
                .thenReturn(List.of());
        when(evaluationRepository.findTrainingRecordIdsWithoutEvaluation(100))
                .thenReturn(List.of());

        reconciler.reconcile();

        verifyNoInteractions(applicationService);
    }
}
