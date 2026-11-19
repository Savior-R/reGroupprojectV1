package com.shijiangtai.m6training.domain.port;

import com.shijiangtai.m6training.domain.TrainingEffectiveConfig;
import com.shijiangtai.m6training.domain.TrainingLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * M4 evaluation seam (08 §8).
 *
 * <p>Trigger publishes {@code TrainingRecordSelectedEvent} so M4 owns evaluation creation. Status
 * reads use {@code m4_eval.*} directly; report reads go through M4's public API (06 §9.2) and are
 * intentionally not duplicated here.
 */
public interface TrainingEvaluationPort {

    void requestEvaluation(EvaluationRequest request);

    Optional<EvaluationState> findState(UUID recordId);

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
}
