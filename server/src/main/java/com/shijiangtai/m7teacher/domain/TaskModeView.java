package com.shijiangtai.m7teacher.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M3 task mode as seen by M7; determines the applicable dimensions (07 §4.1). */
public enum TaskModeView {
    FREE("free", List.of("content", "expression", "interaction", "board", "time")),
    SCENARIO("scenario", List.of("content", "expression", "interaction", "board", "time")),
    INTERVIEW("interview", List.of("content", "expression", "time"));

    private final String wireValue;
    private final List<String> dimensions;

    TaskModeView(String wireValue, List<String> dimensions) {
        this.wireValue = wireValue;
        this.dimensions = dimensions;
    }

    public String wireValue() {
        return wireValue;
    }

    public List<String> dimensions() {
        return dimensions;
    }

    public Map<String, Integer> defaultWeights() {
        LinkedHashMap<String, Integer> weights = new LinkedHashMap<>();
        switch (this) {
            case FREE, SCENARIO -> {
                weights.put("content", 30);
                weights.put("expression", 20);
                weights.put("interaction", 20);
                weights.put("board", 15);
                weights.put("time", 15);
            }
            case INTERVIEW -> {
                weights.put("content", 40);
                weights.put("expression", 30);
                weights.put("time", 30);
            }
        }
        return weights;
    }

    public static TaskModeView fromWire(String value) {
        for (TaskModeView mode : values()) {
            if (mode.wireValue.equals(value)) {
                return mode;
            }
        }
        throw new M7Exception(M7ErrorCode.DATA_INCONSISTENT,
                "任务模式无法识别", Map.of("field", "mode", "value", String.valueOf(value)));
    }
}
