package com.shijiangtai.m4eval.application.view;

import com.shijiangtai.m4eval.domain.Evaluation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Status / metadata view ({@code GET /api/v1/evaluations/{id}}, 06 §9.1). */
public record EvaluationView(
        UUID evaluationId,
        String sourceType,
        UUID sourceId,
        UUID submissionId,
        UUID trainingRecordId,
        UUID sessionId,
        UUID taskId,
        UUID courseId,
        UUID studentAccountId,
        String mode,
        String status,
        BigDecimal totalScore,
        Map<String, Integer> weights,
        List<String> warnings,
        List<String> partialReasons,
        String errorCode,
        String errorMessage,
        String modelVersion,
        String promptVersion,
        String rubricVersion,
        int attemptCount,
        Instant startedAt,
        Instant createdAt,
        Instant completedAt,
        Instant updatedAt) {

    public static EvaluationView from(Evaluation evaluation) {
        return new EvaluationView(
                evaluation.id(),
                evaluation.sourceType(),
                evaluation.sourceId(),
                evaluation.submissionId(),
                evaluation.trainingRecordId(),
                evaluation.sessionId(),
                evaluation.taskId(),
                evaluation.courseId(),
                evaluation.studentAccountId(),
                evaluation.mode().wireValue(),
                evaluation.status().wireValue(),
                evaluation.totalScore(),
                evaluation.weights(),
                evaluation.warnings(),
                evaluation.partialReasons(),
                evaluation.errorCode(),
                evaluation.errorMessage(),
                evaluation.modelVersion(),
                evaluation.promptVersion(),
                evaluation.rubricVersion(),
                evaluation.attemptCount(),
                evaluation.startedAt(),
                evaluation.createdAt(),
                evaluation.completedAt(),
                evaluation.updatedAt());
    }
}
