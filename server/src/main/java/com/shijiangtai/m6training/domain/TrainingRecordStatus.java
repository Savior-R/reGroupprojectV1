package com.shijiangtai.m6training.domain;

/** Training record lifecycle (08 §3.4). */
public enum TrainingRecordStatus {
    SELECTED("selected"),
    EVALUATED("evaluated"),
    DELETED("deleted"),
    EXPIRED("expired");

    private final String wireValue;

    TrainingRecordStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TrainingRecordStatus fromWire(String value) {
        for (TrainingRecordStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new TrainingException(TrainingError.INTERNAL_ERROR,
                "未知记录状态 " + value);
    }

    public boolean active() {
        return this == SELECTED || this == EVALUATED;
    }
}
