package com.shijiangtai.m6training.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;

/** Training tiers (08 §1.1). */
public enum TrainingLevel {
    PROGRESSIVE("progressive"),
    MOCK_EXAM("mock_exam");

    private final String wireValue;

    TrainingLevel(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static TrainingLevel fromWire(String value) {
        for (TrainingLevel level : values()) {
            if (level.wireValue.equals(value)) {
                return level;
            }
        }
        throw new TrainingException(TrainingError.VALIDATION_ERROR,
                "未知训练档位", Map.of("field", "training_level", "value", String.valueOf(value)));
    }
}
