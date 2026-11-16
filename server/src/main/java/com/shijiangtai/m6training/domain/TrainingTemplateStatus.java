package com.shijiangtai.m6training.domain;

/** Template lifecycle (08 §3.1). */
public enum TrainingTemplateStatus {
    PUBLISHED("published"),
    UNPUBLISHED("unpublished");

    private final String wireValue;

    TrainingTemplateStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TrainingTemplateStatus fromWire(String value) {
        for (TrainingTemplateStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new TrainingException(TrainingError.INTERNAL_ERROR,
                "未知模板状态 " + value);
    }
}
