package com.shijiangtai.m3classroom.domain.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class AnswerQualityPolicyTest {

    private final AnswerQualityPolicy policy = new AnswerQualityPolicy();

    @Test
    void qualityBandsAt07And04() {
        AnswerQuality correct = policy.evaluate(0.7, 0.7, new ConstantRandom(0.5));
        assertThat(correct.correctness()).isEqualTo(Correctness.CORRECT);
        assertThat(correct.score()).isCloseTo(0.7, within(1e-9));

        AnswerQuality partial = policy.evaluate(0.4, 0.4, new ConstantRandom(0.5));
        assertThat(partial.correctness()).isEqualTo(Correctness.PARTIAL);
        assertThat(partial.score()).isCloseTo(0.4, within(1e-9));

        AnswerQuality wrong = policy.evaluate(0.39, 0.39, new ConstantRandom(0.5));
        assertThat(wrong.correctness()).isEqualTo(Correctness.WRONG);

        assertThat(policy.evaluate(0.69, 0.69, new ConstantRandom(0.5)).correctness())
                .isEqualTo(Correctness.PARTIAL);
    }

    @Test
    void qualityIsClampedToZeroOne() {
        AnswerQuality clampedHigh = policy.evaluate(1.0, 1.0, new ConstantRandom(1.0));
        assertThat(clampedHigh.score()).isEqualTo(1.0);
        assertThat(clampedHigh.correctness()).isEqualTo(Correctness.CORRECT);

        AnswerQuality clampedLow = policy.evaluate(0.0, 0.0, new ConstantRandom(0.0));
        assertThat(clampedLow.score()).isZero();
        assertThat(clampedLow.correctness()).isEqualTo(Correctness.WRONG);
    }

    @Test
    void usesExactWeightsAndNoiseRange() {
        // ability*0.7 + clarity*0.3; a 0.5 draw adds no noise.
        assertThat(policy.evaluate(1.0, 0.0, new ConstantRandom(0.5)).score())
                .isCloseTo(0.7, within(1e-9));
        assertThat(policy.evaluate(0.0, 1.0, new ConstantRandom(0.5)).score())
                .isCloseTo(0.3, within(1e-9));

        // random(-0.15, 0.15): draw 1.0 -> +0.15, draw 0.0 -> -0.15.
        assertThat(policy.evaluate(0.5, 0.5, new ConstantRandom(1.0)).score())
                .isCloseTo(0.65, within(1e-9));
        assertThat(policy.evaluate(0.5, 0.5, new ConstantRandom(0.0)).score())
                .isCloseTo(0.35, within(1e-9));
    }

    private static final class ConstantRandom implements RandomGenerator {

        private final double value;

        private ConstantRandom(double value) {
            this.value = value;
        }

        @Override
        public long nextLong() {
            return 0L;
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }
}
