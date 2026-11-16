package com.shijiangtai.m6training.domain.port;

import com.shijiangtai.m6training.domain.TrainingEffectiveConfig;
import com.shijiangtai.m6training.domain.TrainingLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * M4 evaluation seam (08 §8).
 *
 * <p>Trigger publishes {@code TrainingRecordSelectedEvent} so M4 owns evaluation creation. Reads
 * come from {@code m4_eval.*} read-only until M4 exposes a report API.
 */
public interface TrainingEvaluationPort {

    void requestEvaluation(EvaluationRequest request);

    Optional<EvaluationState> findState(UUID recordId);

    Optional<EvaluationReport> findReport(UUID recordId);

    void deleteByRecord(UUID recordId);

    record EvaluationRequest(
            UUID recordId,
            UUID sessionId,
            UUID studentAccountId,
            UUID courseId,
            TrainingLevel trainingLevel,
            TrainingEffectiveConfig config) {
    }

    record EvaluationState(
            UUID evaluationId,
            String status,
            BigDecimal totalScore,
            String errorCode,
            String errorMessage,
            Instant completedAt) {
    }

    record EvaluationReport(
            UUID evaluationId,
            String status,
            BigDecimal totalScore,
            List<DimensionScore> dimensions,
            List<Issue> issues,
            List<String> warnings,
            Instant completedAt) {
    }

    record DimensionScore(
            String dimension,
            BigDecimal score,
            BigDecimal confidence,
            String subScoresJson) {
    }

    record Issue(
            String dimension,
            Long startMs,
            Long endMs,
            String severity,
            String evidence,
            String suggestion,
            boolean isSuggestion) {
    }
}
