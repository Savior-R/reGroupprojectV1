package com.shijiangtai.m6training.domain;

import java.util.Map;

/** Domain failure carrying an M6 error code and response details. */
public class TrainingException extends RuntimeException {
    private final TrainingError error;
    private final Map<String, Object> details;

    public TrainingException(TrainingError error, String message) {
        this(error, message, Map.of());
    }

    public TrainingException(TrainingError error, String message, Map<String, Object> details) {
        super(message);
        this.error = error;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public TrainingError error() {
        return error;
    }

    public Map<String, Object> details() {
        return details;
    }
}
