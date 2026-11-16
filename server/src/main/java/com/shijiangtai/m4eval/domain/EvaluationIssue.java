package com.shijiangtai.m4eval.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One located issue or improvement suggestion (06 §3.5, {@code m4.issues}). */
public record EvaluationIssue(
        UUID id,
        UUID evaluationId,
        String dimension,
        Long startMs,
        Long endMs,
        String severity,
        String evidence,
        String suggestion,
        boolean isSuggestion,
        Integer suggestionRank,
        int sortOrder) {

    public static final Set<String> SEVERITIES = Set.of("high", "medium", "low");
    public static final int MAX_EVIDENCE_CHARS = 500;
    public static final int MAX_SUGGESTION_CHARS = 500;

    public EvaluationIssue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(suggestion, "suggestion");
        if (startMs != null && startMs < 0) {
            throw new IllegalArgumentException("startMs must not be negative");
        }
        if (endMs != null && endMs < 0) {
            throw new IllegalArgumentException("endMs must not be negative");
        }
        if (startMs != null && endMs != null && startMs > endMs) {
            throw new IllegalArgumentException("startMs must not exceed endMs");
        }
        if (suggestionRank != null && suggestionRank < 1) {
            throw new IllegalArgumentException("suggestionRank must be positive");
        }
    }

    public EvaluationIssue withSortOrder(int order) {
        return new EvaluationIssue(
                id, evaluationId, dimension, startMs, endMs, severity, evidence, suggestion,
                isSuggestion, suggestionRank, order);
    }
}
