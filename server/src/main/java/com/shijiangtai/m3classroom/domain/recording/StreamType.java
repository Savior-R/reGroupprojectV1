package com.shijiangtai.m3classroom.domain.recording;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

public enum StreamType {
    CAMERA("camera"),
    SCREEN("screen"),
    AUDIO("audio");

    private final String wireValue;

    StreamType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static StreamType fromWire(String value) {
        for (StreamType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new DomainException(ErrorCode.VALIDATION_ERROR,
                "未知录制流类型",
                Map.of("field", "stream_type", "value", String.valueOf(value)));
    }
}
