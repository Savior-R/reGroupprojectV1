package com.shijiangtai.m6training.domain;

/** Training session states on top of {@code m3_classroom.sessions} (08 §6.2). */
public enum TrainingSessionStatus {
    PREPARING("preparing"),
    IN_PROGRESS("in_progress"),
    ENDED("ended"),
    REVIEWING("reviewing"),
    SELECTED("selected"),
    EVALUATED("evaluated"),
    CANCELLED("cancelled"),
    FAILED("failed");

    private final String wireValue;

    TrainingSessionStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TrainingSessionStatus fromWire(String value) {
        for (TrainingSessionStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new TrainingException(TrainingError.INTERNAL_ERROR,
                "未知训练状态 " + value);
    }
}
