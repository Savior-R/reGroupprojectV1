package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.List;
import java.util.Map;

public enum TaskMode {
    FREE("free"),
    SCENARIO("scenario"),
    INTERVIEW("interview");

    private static final List<String> FREE_DIMENSIONS =
            List.of("content", "expression", "interaction", "board", "time");
    private static final List<String> INTERVIEW_DIMENSIONS =
            List.of("content", "expression", "time");

    private final String wireValue;

    TaskMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TaskMode fromWire(String value) {
        for (TaskMode mode : values()) {
            if (mode.wireValue.equals(value)) {
                return mode;
            }
        }
        throw new DomainException(ErrorCode.TASK_CONFIG_INVALID,
                "未知任务模式", Map.of("field", "mode"));
    }

    public List<String> dimensions() {
        return this == INTERVIEW ? INTERVIEW_DIMENSIONS : FREE_DIMENSIONS;
    }

    public RubricWeights defaultWeights() {
        return RubricWeights.defaults(this);
    }

    public int defaultRecommendedSeconds() {
        return this == INTERVIEW ? 300 : 900;
    }

    public int defaultMaxSeconds() {
        return this == INTERVIEW ? 300 : 1200;
    }

    public boolean requiresMaterial() {
        return this != INTERVIEW;
    }

    public boolean requiresVirtualStudents() {
        return this != INTERVIEW;
    }
}
