package com.shijiangtai.m3classroom.domain.classroom;

import java.util.List;
import java.util.Objects;

/**
 * Result of classifying one teacher utterance.
 *
 * <p>{@code primary} is the highest-precedence intent; callers should usually test
 * {@link #has(TeacherIntent)} instead of only reading the primary intent, because comments,
 * sit-down instructions and discipline actions may coexist. {@code studentName} is the
 * normalized name matched from the task roster, or {@code null} when no unique candidate was
 * found.
 */
public record TeacherUtteranceIntent(
        List<TeacherIntentHit> hits,
        TeacherIntent primary,
        double confidence,
        String studentName,
        String polarity,
        String modelVersion) {

    public TeacherUtteranceIntent {
        Objects.requireNonNull(hits, "hits");
        hits = List.copyOf(hits);
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(modelVersion, "modelVersion");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0, 1]");
        }
    }

    public boolean has(TeacherIntent intent) {
        return confidenceOf(intent) > 0.0;
    }

    /** Returns the confidence for {@code intent}, or {@code 0.0} when it was not detected. */
    public double confidenceOf(TeacherIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return hits.stream()
                .filter(hit -> hit.intent() == intent)
                .mapToDouble(TeacherIntentHit::confidence)
                .max()
                .orElse(0.0);
    }
}
