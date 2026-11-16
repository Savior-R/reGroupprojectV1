package com.shijiangtai.m6training.domain;

import java.util.Map;

/** Recommended / maximum duration in seconds (08 §3.2). */
public record TrainingDuration(int recommendedSeconds, int maxSeconds) {

    public static final int MIN_SECONDS = 1;
    public static final int MAX_SECONDS = 3600;

    public TrainingDuration {
        if (recommendedSeconds < MIN_SECONDS || recommendedSeconds > MAX_SECONDS
                || maxSeconds < MIN_SECONDS || maxSeconds > MAX_SECONDS
                || recommendedSeconds > maxSeconds) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "训练时长非法", Map.of("field", "duration"));
        }
    }

    public long recommendedMillis() {
        return recommendedSeconds * 1000L;
    }

    public long maxMillis() {
        return maxSeconds * 1000L;
    }

    public TrainingDuration fixedToMax() {
        return new TrainingDuration(maxSeconds, maxSeconds);
    }
}
