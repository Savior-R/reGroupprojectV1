package com.shijiangtai.m6training.domain;

/** Whether a material belongs to a free session or a personal template (08 §9). */
public enum TrainingMaterialUsage {
    FREE_SESSION("free_session"),
    TEMPLATE("template");

    private final String wireValue;

    TrainingMaterialUsage(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TrainingMaterialUsage fromWire(String value) {
        for (TrainingMaterialUsage usage : values()) {
            if (usage.wireValue.equals(value)) {
                return usage;
            }
        }
        throw new TrainingException(TrainingError.INTERNAL_ERROR,
                "未知材料用途 " + value);
    }
}
