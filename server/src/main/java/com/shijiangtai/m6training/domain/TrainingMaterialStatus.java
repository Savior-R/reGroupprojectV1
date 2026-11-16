package com.shijiangtai.m6training.domain;

/** Free-training material lifecycle (08 §9). */
public enum TrainingMaterialStatus {
    UPLOADED("uploaded"),
    USED("used"),
    DELETED("deleted"),
    EXPIRED("expired");

    private final String wireValue;

    TrainingMaterialStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TrainingMaterialStatus fromWire(String value) {
        for (TrainingMaterialStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new TrainingException(TrainingError.INTERNAL_ERROR,
                "未知材料状态 " + value);
    }
}
