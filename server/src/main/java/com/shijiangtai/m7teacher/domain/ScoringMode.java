package com.shijiangtai.m7teacher.domain;

import java.util.Locale;
import java.util.Map;

/** Task-level teacher scoring mode (07 §3.1). */
public enum ScoringMode {
    DIMENSION("dimension"),
    TOTAL("total");

    private final String wireValue;

    ScoringMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static ScoringMode fromWire(String value) {
        if (value != null) {
            for (ScoringMode mode : values()) {
                if (mode.wireValue.equals(value.toLowerCase(Locale.ROOT))) {
                    return mode;
                }
            }
        }
        throw new M7Exception(M7ErrorCode.GRADING_CONFIG_INVALID,
                "打分模式必须为 dimension 或 total", Map.of("field", "scoring_mode"));
    }
}
