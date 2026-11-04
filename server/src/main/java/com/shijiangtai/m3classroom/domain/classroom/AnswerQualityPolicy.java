package com.shijiangtai.m3classroom.domain.classroom;

import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Answer-quality evaluation for a called-on virtual student (05A §5.5).
 *
 * <p>{@code answer_quality = clamp(ability*0.7 + question_clarity*0.3 + random(-0.15, 0.15),
 * 0, 1)}; {@code >= 0.7} is {@link Correctness#CORRECT}, {@code >= 0.4} is
 * {@link Correctness#PARTIAL}, and anything lower is {@link Correctness#WRONG}.
 *
 * <p>{@code question_clarity} defaults to {@code 0.5} until teacher-speech classification is
 * available; the caller supplies the current value.
 */
public final class AnswerQualityPolicy {

    private static final double ABILITY_WEIGHT = 0.7;
    private static final double QUESTION_CLARITY_WEIGHT = 0.3;
    private static final double NOISE_AMPLITUDE = 0.15;
    private static final double CORRECT_THRESHOLD = 0.7;
    private static final double PARTIAL_THRESHOLD = 0.4;
    /**
     * Inclusive band boundaries must survive double rounding, e.g. {@code 0.4*0.7 + 0.4*0.3}
     * evaluates to {@code 0.39999999999999997} but is the exact 0.4 boundary from 05A §5.5.
     */
    private static final double BAND_EPSILON = 1e-9;

    public AnswerQuality evaluate(
            double ability, double questionClarity, RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        double noise = (random.nextDouble() * 2.0 - 1.0) * NOISE_AMPLITUDE;
        double score = clamp(
                ability * ABILITY_WEIGHT + questionClarity * QUESTION_CLARITY_WEIGHT + noise,
                0.0, 1.0);
        Correctness correctness;
        if (score >= CORRECT_THRESHOLD - BAND_EPSILON) {
            correctness = Correctness.CORRECT;
        } else if (score >= PARTIAL_THRESHOLD - BAND_EPSILON) {
            correctness = Correctness.PARTIAL;
        } else {
            correctness = Correctness.WRONG;
        }
        return new AnswerQuality(correctness, score);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
