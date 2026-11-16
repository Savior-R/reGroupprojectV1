package com.shijiangtai.m7teacher.domain;

/** Final-score status buckets (07 §6.3). */
public enum FinalScoreStatus {
    PENDING_AI("pending_ai"),
    PENDING_TEACHER("pending_teacher"),
    COMPLETED("completed"),
    AI_PARTIAL("ai_partial"),
    AI_FAILED("ai_failed");

    private final String wireValue;

    FinalScoreStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static FinalScoreStatus fromWire(String value) {
        for (FinalScoreStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown final score status: " + value);
    }
}
