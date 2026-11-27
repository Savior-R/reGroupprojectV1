package com.shijiangtai.m6training.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** How a personal template was created (08 §3.1). */
public enum TrainingTemplateSourceType {
    MANUAL("manual"),
    TASK("task"),
    TEMPLATE("template");

    private final String wireValue;

    TrainingTemplateSourceType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public static TrainingTemplateSourceType fromWire(String value) {
        for (TrainingTemplateSourceType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new TrainingException(TrainingError.INTERNAL_ERROR,
                "未知模板来源 " + value);
    }
}
