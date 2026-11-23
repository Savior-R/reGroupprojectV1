package com.shijiangtai.m4eval.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the worker result callback before anything is persisted (06 §5.3, 05 §4.2).
 *
 * <p>The callback never carries a total; Java composes it from the snapshot weights. Invalid
 * payloads are rejected as a whole — no half-valid reporting row is written.
 */
public final class EvaluationResultValidator {

    public static final int MAX_ISSUES = 100;
    public static final int MAX_DIMENSIONS = 5;
    public static final BigDecimal MIN_SCORE = BigDecimal.ZERO;
    public static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);

    private EvaluationResultValidator() {
    }

    /**
     * @throws EvaluationException {@code EVALUATION_INVALID_RESULT} on the first violation
     */
    public static void validate(
            EvaluationMode mode,
            Map<String, Integer> weights,
            List<DimensionScore> dimensions,
            List<EvaluationIssue> issues) {
        if (dimensions.size() > MAX_DIMENSIONS) {
            throw invalid("too many dimensions: " + dimensions.size());
        }
        if (issues.size() > MAX_ISSUES) {
            throw invalid("too many issues: " + issues.size());
        }

        Set<String> applicable = Set.copyOf(DimensionCatalog.applicableDimensions(mode));
        Set<String> seen = new HashSet<>();
        for (DimensionScore dimension : dimensions) {
            String name = dimension.dimension();
            if (!DimensionCatalog.isKnownDimension(name) || !applicable.contains(name)) {
                throw invalid("dimension not applicable to " + mode.wireValue() + ": " + name);
            }
            if (!seen.add(name)) {
                throw invalid("duplicate dimension: " + name);
            }
            if (!weights.containsKey(name)) {
                throw invalid("dimension missing from snapshot weights: " + name);
            }
            checkScore("dimension " + name, dimension.score());
            validateSubScores(dimension);
            checkConfidence("dimension " + name, dimension.confidence());
        }

        for (EvaluationIssue issue : issues) {
            if (!DimensionCatalog.isKnownDimension(issue.dimension())) {
                throw invalid("issue has unknown dimension: " + issue.dimension());
            }
            if (!applicable.contains(issue.dimension())) {
                throw invalid(
                        "issue dimension not applicable to " + mode.wireValue() + ": "
                                + issue.dimension());
            }
            if (!EvaluationIssue.SEVERITIES.contains(issue.severity())) {
                throw invalid("issue has invalid severity: " + issue.severity());
            }
            if (issue.startMs() != null && issue.startMs() < 0) {
                throw invalid("issue start_ms must not be negative");
            }
            if (issue.endMs() != null && issue.endMs() < 0) {
                throw invalid("issue end_ms must not be negative");
            }
            if (issue.startMs() != null && issue.endMs() != null
                    && issue.endMs() < issue.startMs()) {
                throw invalid("issue end_ms precedes start_ms");
            }
            if (issue.isSuggestion() && issue.startMs() == null) {
                throw invalid("suggestion requires a start_ms anchor");
            }
            if (issue.evidence() != null
                    && issue.evidence().length() > EvaluationIssue.MAX_EVIDENCE_CHARS) {
                throw invalid("issue evidence exceeds " + EvaluationIssue.MAX_EVIDENCE_CHARS);
            }
            if (issue.suggestion() != null
                    && issue.suggestion().length() > EvaluationIssue.MAX_SUGGESTION_CHARS) {
                throw invalid("issue suggestion exceeds " + EvaluationIssue.MAX_SUGGESTION_CHARS);
            }
            if (issue.suggestionRank() != null && issue.suggestionRank() < 0) {
                throw invalid("suggestion_rank must not be negative");
            }
        }
    }

    /** Validates and rounds a dimension list to the stored precision. */
    public static List<DimensionScore> normalize(List<DimensionScore> dimensions) {
        return dimensions.stream().map(DimensionScore::rounded).toList();
    }

    /** Converts a validated dimension list into the score map used by the policy. */
    public static Map<String, BigDecimal> scoreMap(List<DimensionScore> dimensions) {
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        for (DimensionScore dimension : dimensions) {
            scores.put(dimension.dimension(), dimension.score());
        }
        return scores;
    }

    private static void validateSubScores(DimensionScore dimension) {
        Set<String> expected = DimensionCatalog.subItems(dimension.dimension());
        Map<String, BigDecimal> subScores = dimension.subScores();
        // Sub-items may be absent when evidence is unavailable (06 §6.1: a missing reference
        // material removes accuracy while the remaining sub-items still score the dimension).
        if (subScores.isEmpty()) {
            throw invalid("dimension " + dimension.dimension()
                    + " requires at least one sub-item score");
        }
        for (Map.Entry<String, BigDecimal> entry : subScores.entrySet()) {
            if (!expected.contains(entry.getKey())) {
                throw invalid(
                        "unknown sub-item " + entry.getKey() + " for " + dimension.dimension());
            }
            checkScore("sub-item " + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, BigDecimal> entry : dimension.subConfidences().entrySet()) {
            if (!expected.contains(entry.getKey())) {
                throw invalid(
                        "unknown sub-confidence " + entry.getKey() + " for "
                                + dimension.dimension());
            }
            checkUnit("sub-confidence " + entry.getKey(), entry.getValue());
        }
    }

    private static void checkScore(String field, BigDecimal score) {
        if (score == null) {
            throw invalid(field + " score is required");
        }
        if (score.compareTo(MIN_SCORE) < 0 || score.compareTo(MAX_SCORE) > 0) {
            throw invalid(field + " score out of range: " + score);
        }
        if (score.scale() > 1 && score.stripTrailingZeros().scale() > 1) {
            throw invalid(field + " score must keep at most one decimal: " + score);
        }
    }

    private static void checkConfidence(String field, BigDecimal confidence) {
        if (confidence == null) {
            return;
        }
        checkUnit(field + " confidence", confidence);
    }

    private static void checkUnit(String field, BigDecimal value) {
        if (value == null) {
            throw invalid(field + " is required");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw invalid(field + " out of range: " + value);
        }
    }

    private static EvaluationException invalid(String message) {
        return new EvaluationException(
                EvaluationErrorCode.EVALUATION_INVALID_RESULT, message, Map.of());
    }
}
