package com.shijiangtai.m4eval.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Computed terminal outcome for an evaluation.
 *
 * <p>{@code totalScore} is non-null only for {@link EvaluationStatus#COMPLETED}; partial and
 * failed evaluations never expose a half-finished total (06 §3.3, §6.7).
 */
public record EvaluationOutcome(
        EvaluationStatus status,
        BigDecimal totalScore,
        List<String> warnings,
        List<String> partialReasons,
        String modelVersion,
        String promptVersion,
        String rubricVersion,
        Map<String, Object> tokenUsage,
        Instant completedAt) {

    public EvaluationOutcome {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        partialReasons = partialReasons == null ? List.of() : List.copyOf(partialReasons);
        tokenUsage = tokenUsage == null ? Map.of() : Map.copyOf(tokenUsage);
        if (status == EvaluationStatus.COMPLETED && totalScore == null) {
            throw new IllegalArgumentException("completed outcome requires totalScore");
        }
        if (status != EvaluationStatus.COMPLETED && totalScore != null) {
            throw new IllegalArgumentException("only completed outcome may carry totalScore");
        }
    }
}
