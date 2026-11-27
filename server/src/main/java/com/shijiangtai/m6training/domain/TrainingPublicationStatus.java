package com.shijiangtai.m6training.domain;

/** Course publication lifecycle (08 §3.2). */
public enum TrainingPublicationStatus {
    PUBLISHED("published"),
    UNPUBLISHED("unpublished");

    private final String wireValue;

    TrainingPublicationStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TrainingPublicationStatus fromWire(String value) {
        for (TrainingPublicationStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new TrainingException(TrainingError.INTERNAL_ERROR,
                "未知发布状态 " + value);
    }
}
