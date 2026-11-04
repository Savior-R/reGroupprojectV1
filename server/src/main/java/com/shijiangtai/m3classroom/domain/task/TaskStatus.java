package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

public enum TaskStatus {
    DRAFT("draft"),
    PUBLISHED("published"),
    CLOSED("closed");

    private final String wireValue;

    TaskStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TaskStatus fromWire(String value) {
        for (TaskStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                "未知任务状态", Map.of("field", "status"));
    }
}
