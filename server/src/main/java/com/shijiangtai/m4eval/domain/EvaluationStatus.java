package com.shijiangtai.m4eval.domain;

import java.util.Locale;

/** Evaluation lifecycle (06 §3.4). {@code completed / partial / failed} are terminal. */
public enum EvaluationStatus {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    PARTIAL("partial"),
    FAILED("failed");

    private final String wireValue;

    EvaluationStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public boolean terminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED;
    }

    public static EvaluationStatus fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
