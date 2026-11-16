package com.shijiangtai.m4eval.domain;

import java.util.Locale;

/** Queue-side job state (06 §5.1). Java is the state authority. */
public enum EvaluationJobStatus {
    QUEUED("queued"),
    RUNNING("running"),
    COMPLETED("completed"),
    PARTIAL("partial"),
    FAILED("failed");

    private final String wireValue;

    EvaluationJobStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public boolean terminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED;
    }

    public static EvaluationJobStatus fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("job status must not be null");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
