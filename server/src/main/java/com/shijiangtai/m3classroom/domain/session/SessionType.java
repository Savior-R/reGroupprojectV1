package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

public enum SessionType {
    TASK("task");

    private final String wireValue;

    SessionType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SessionType fromWire(String value) {
        for (SessionType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                "未知会话类型",
                Map.of("field", "session_type", "value", String.valueOf(value)));
    }
}
