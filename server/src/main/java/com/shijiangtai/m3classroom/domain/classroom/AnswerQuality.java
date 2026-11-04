package com.shijiangtai.m3classroom.domain.classroom;

import java.util.Objects;

/**
 * Result of {@link AnswerQualityPolicy#evaluate(double, double, java.util.random.RandomGenerator)}:
 * the banded {@link Correctness} and the raw quality score in {@code [0, 1]} (05A §5.5).
 */
public record AnswerQuality(Correctness correctness, double score) {

    public AnswerQuality {
        Objects.requireNonNull(correctness, "correctness");
    }
}
