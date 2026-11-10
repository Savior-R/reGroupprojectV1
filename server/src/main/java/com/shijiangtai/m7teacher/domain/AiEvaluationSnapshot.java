package com.shijiangtai.m7teacher.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Read-only M4 evaluation projection consumed by the calculator. */
public record AiEvaluationSnapshot(
        UUID evaluationId,
        String status,
        BigDecimal totalScore,
        Map<String, BigDecimal> dimensionScores,
        String errorCode,
        String errorMessage) {

    public AiEvaluationSnapshot {
        dimensionScores = dimensionScores == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(dimensionScores));
    }

    public boolean completed() {
        return "completed".equals(status);
    }

    public boolean partial() {
        return "partial".equals(status);
    }

    public boolean failed() {
        return "failed".equals(status);
    }

    public boolean pending() {
        return "pending".equals(status) || "running".equals(status);
    }
}
