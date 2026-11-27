package com.shijiangtai.m6training.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** Personal-template owner role snapshot (08 §3.1). */
public enum TrainingOwnerRole {
    TEACHER("teacher"),
    STUDENT("student");

    private final String wireValue;

    TrainingOwnerRole(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public static TrainingOwnerRole fromWire(String value) {
        for (TrainingOwnerRole role : values()) {
            if (role.wireValue.equals(value)) {
                return role;
            }
        }
        throw new TrainingException(TrainingError.TRAINING_TEMPLATE_INVALID,
                "未知模板角色 " + value);
    }
}
