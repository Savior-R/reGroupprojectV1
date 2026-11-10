package com.shijiangtai.m4eval.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One applicable dimension score of an evaluation (06 §3.1). */
public record DimensionScore(
        UUID id,
        UUID evaluationId,
        String dimension,
        BigDecimal score,
        Map<String, BigDecimal> subScores,
        Map<String, BigDecimal> subConfidences,
        BigDecimal confidence) {

    public DimensionScore {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(score, "score");
        subScores = copy(subScores);
        subConfidences = copy(subConfidences);
        Objects.requireNonNull(confidence, "confidence");
    }

    /** Dimension score and confidence keep one / two decimals respectively (06 §3.3). */
    public DimensionScore rounded() {
        return new DimensionScore(
                id,
                evaluationId,
                dimension,
                score.setScale(1, RoundingMode.HALF_UP),
                roundAll(subScores, 1),
                roundAll(subConfidences, 2),
                confidence.setScale(2, RoundingMode.HALF_UP));
    }

    private static Map<String, BigDecimal> copy(Map<String, BigDecimal> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, BigDecimal> roundAll(
            Map<String, BigDecimal> source, int scale) {
        if (source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, BigDecimal> rounded = new LinkedHashMap<>();
        source.forEach((key, value) -> rounded.put(
                key, value == null ? null : value.setScale(scale, RoundingMode.HALF_UP)));
        return Collections.unmodifiableMap(rounded);
    }
}
