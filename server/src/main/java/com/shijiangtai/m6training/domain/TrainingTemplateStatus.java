package com.shijiangtai.m6training.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** Personal-template lifecycle (08 §3.1): UI shows archived as "deleted". */
public enum TrainingTemplateStatus {
    ACTIVE("active"),
    ARCHIVED("archived");

    private final String wireValue;

    TrainingTemplateStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
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
