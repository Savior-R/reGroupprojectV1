package com.shijiangtai.m3classroom.support;

import java.util.random.RandomGenerator;

/**
 * Deterministic test seam: {@link #nextDouble()} always returns the current mutable value and
 * {@link #nextLong()} always returns {@code 0}, so probability rolls (for example the follow-up
 * decision) can be steered per test without seeding a real generator.
 */
public final class MutableRandom implements RandomGenerator {

    private double value;

    public MutableRandom(double value) {
        this.value = value;
    }

    public void set(double value) {
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
