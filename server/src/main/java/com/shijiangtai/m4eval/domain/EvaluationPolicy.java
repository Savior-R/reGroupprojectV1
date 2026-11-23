package com.shijiangtai.m4eval.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Status and score composition (06 §3.3, §3.4, §6.7).
 *
 * <p>Only dimensions with {@code weight > 0} gate completion. A missing zero-weight dimension is a
 * warning, not a partial result.
 */
public final class EvaluationPolicy {

    public static final Set<String> PARTIAL_REASONS = Set.of(
            "TRANSCRIPT_MISSING",
            "AUDIO_MISSING",
            "FRAMES_MISSING",
            "ANSWER_POINTS_MISSING",
            "DIMENSION_FAILED",
            "DIMENSION_TIMEOUT",
            "INVALID_MODEL_OUTPUT");

    public static final Set<String> WARNINGS = Set.of(
            "REFERENCE_MISSING",
            "EMPTY_EVENTS",
            "OCR_LOW_CONFIDENCE",
            "CONTEXT_TRUNCATED",
            "LOW_ASR_CONFIDENCE");

    public static final String ZERO_WEIGHT_MISSING = "ZERO_WEIGHT_DIMENSION_MISSING";
    public static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);

    /**
     * Input reasons that only gate completion while one of their dimensions has weight &gt; 0
     * (06 §3.2 / §6.7: a missing zero-weight dimension is a warning, not a partial result).
     */
    private static final Map<String, Set<String>> REASON_DIMENSIONS = Map.of(
            "TRANSCRIPT_MISSING", Set.of("content", "expression", "time"),
            "AUDIO_MISSING", Set.of("expression"),
            "FRAMES_MISSING", Set.of("board"),
            "ANSWER_POINTS_MISSING", Set.of("content"));

    /** Reasons reported per dimension; whether they gate completion depends on that dimension. */
    private static final Set<String> DIMENSION_REASONS = Set.of(
            "DIMENSION_FAILED", "DIMENSION_TIMEOUT", "INVALID_MODEL_OUTPUT");

    private EvaluationPolicy() {
    }

    /**
     * Weighted total over applicable dimensions, rounded half-up to one decimal.
     *
     * @throws IllegalArgumentException when a required dimension is absent or the weight sum is
     *     not 100; the caller must not compute a partial total
     */
    public static BigDecimal totalScore(
            Map<String, Integer> weights, Map<String, BigDecimal> dimensionScores) {
        int weightSum = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            int weight = entry.getValue();
            if (weight == 0) {
                continue;
            }
            BigDecimal score = dimensionScores.get(entry.getKey());
            if (score == null) {
                throw new IllegalArgumentException(
                        "missing required dimension: " + entry.getKey());
            }
            weightSum += weight;
            total = total.add(score.multiply(BigDecimal.valueOf(weight)));
        }
        if (weightSum != 100) {
            throw new IllegalArgumentException("weights must sum to 100, was " + weightSum);
        }
        return total.divide(BigDecimal.valueOf(100), 1, RoundingMode.HALF_UP);
    }

    /**
     * Decides the terminal status from the returned dimensions.
     *
     * <ul>
     *   <li>no dimension at all → {@code failed};
     *   <li>a required dimension missing or any partial reason → {@code partial};
     *   <li>otherwise → {@code completed} with the weighted total.
     * </ul>
     */
    public static EvaluationOutcome decide(
            EvaluationMode mode,
            Map<String, Integer> weights,
            Map<String, BigDecimal> dimensionScores,
            List<String> returnedWarnings,
            List<String> returnedPartialReasons,
            String modelVersion,
            String promptVersion,
            String rubricVersion,
            Map<String, Object> tokenUsage,
            Instant now) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(weights, "weights");
        Objects.requireNonNull(now, "now");

        List<String> warnings = new ArrayList<>(distinct(returnedWarnings));
        List<String> partialReasons = new ArrayList<>(distinct(returnedPartialReasons));
        if (dimensionScores.isEmpty()) {
            return new EvaluationOutcome(
                    EvaluationStatus.FAILED, null, warnings, partialReasons, modelVersion,
                    promptVersion, rubricVersion, tokenUsage, now);
        }

        boolean missingRequired = false;
        for (String dimension : DimensionCatalog.applicableDimensions(mode)) {
            int weight = weights.getOrDefault(dimension, 0);
            if (!dimensionScores.containsKey(dimension)) {
                if (weight > 0) {
                    missingRequired = true;
                    partialReasons.add("DIMENSION_FAILED");
                } else {
                    warnings.add(ZERO_WEIGHT_MISSING);
                }
            }
        }
        warnings = new ArrayList<>(distinct(warnings));
        partialReasons = new ArrayList<>(distinct(partialReasons));

        // Only reasons that affect a weight>0 dimension may gate completion. A worker that
        // reports FRAMES_MISSING while board has weight 0 must not turn a full report partial.
        List<String> effectiveReasons = new ArrayList<>();
        for (String reason : partialReasons) {
            if (gatesCompletion(reason, weights, missingRequired)) {
                effectiveReasons.add(reason);
            }
        }

        if (missingRequired || !effectiveReasons.isEmpty()) {
            return new EvaluationOutcome(
                    EvaluationStatus.PARTIAL, null, warnings, effectiveReasons, modelVersion,
                    promptVersion, rubricVersion, tokenUsage, now);
        }
        BigDecimal total = totalScore(weights, dimensionScores);
        return new EvaluationOutcome(
                EvaluationStatus.COMPLETED, total, warnings, effectiveReasons, modelVersion,
                promptVersion, rubricVersion, tokenUsage, now);
    }

    /** Dead-letter outcome: keep finished dimensions, otherwise fail. */
    public static EvaluationOutcome deadLetter(
            Map<String, BigDecimal> finishedDimensions, String error) {
        List<String> reasons = List.of("DIMENSION_TIMEOUT");
        if (finishedDimensions == null || finishedDimensions.isEmpty()) {
            return new EvaluationOutcome(
                    EvaluationStatus.FAILED, null, List.of(), reasons, null, null,
                    DimensionCatalog.RUBRIC_VERSION, Map.of(), Instant.now());
        }
        return new EvaluationOutcome(
                EvaluationStatus.PARTIAL, null, List.of(), reasons, null, null,
                DimensionCatalog.RUBRIC_VERSION, Map.of(), Instant.now());
    }

    private static boolean gatesCompletion(
            String reason, Map<String, Integer> weights, boolean missingRequired) {
        Set<String> dimensions = REASON_DIMENSIONS.get(reason);
        if (dimensions != null) {
            for (String dimension : dimensions) {
                if (weights.getOrDefault(dimension, 0) > 0) {
                    return true;
                }
            }
            return false;
        }
        if (DIMENSION_REASONS.contains(reason)) {
            // A failed dimension is already reflected by the missing-dimension check. Without a
            // missing required dimension the failure must belong to a zero-weight dimension.
            return missingRequired;
        }
        // Unknown reasons are kept: rejecting a report is safer than silently completing it.
        return true;
    }

    private static List<String> distinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                distinct.add(value);
            }
        }
        return List.copyOf(distinct);
    }
}
