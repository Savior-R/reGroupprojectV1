package com.shijiangtai.m4eval.domain;

import java.util.Locale;

/** Evaluated classroom mode (06 §3.2). */
public enum EvaluationMode {
    FREE("free"),
    SCENARIO("scenario"),
    INTERVIEW("interview");

    private final String wireValue;

    EvaluationMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static EvaluationMode fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
