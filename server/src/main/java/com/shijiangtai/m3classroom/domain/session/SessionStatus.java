package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

public enum SessionStatus {
    PREPARING("preparing"),
    IN_PROGRESS("in_progress"),
    ENDED("ended"),
    REVIEWING("reviewing"),
    SUBMITTED("submitted"),
    /**
     * M6 training terminal statuses. M6 writes them into the shared
     * {@code m3_classroom.sessions} row after a training record is selected/evaluated;
     * M3 only parses them for replay/read paths and never transitions into them.
     */
    SELECTED("selected"),
    EVALUATED("evaluated"),
    CANCELLED("cancelled"),
    FAILED("failed");

    private final String wireValue;

    SessionStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SessionStatus fromWire(String value) {
        for (SessionStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                "未知会话状态",
                Map.of("field", "status", "value", String.valueOf(value)));
    }
}
