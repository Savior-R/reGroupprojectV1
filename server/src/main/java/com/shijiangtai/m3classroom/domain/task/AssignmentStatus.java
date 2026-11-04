package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

public enum AssignmentStatus {
    ASSIGNED("assigned"),
    SUBMITTED("submitted");

    private final String wireValue;

    AssignmentStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static AssignmentStatus fromWire(String value) {
        for (AssignmentStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new DomainException(ErrorCode.TASK_CONFIG_INVALID,
                "未知分配状态", Map.of("status", value));
    }
}
