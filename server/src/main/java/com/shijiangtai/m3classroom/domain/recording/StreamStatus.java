package com.shijiangtai.m3classroom.domain.recording;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

public enum StreamStatus {
    UPLOADING("uploading"),
    UPLOADED("uploaded"),
    FAILED("failed"),
    DELETED("deleted");

    private final String wireValue;

    StreamStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static StreamStatus fromWire(String value) {
        for (StreamStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new DomainException(ErrorCode.VALIDATION_ERROR,
                "未知录制流状态",
                Map.of("field", "stream_status", "value", String.valueOf(value)));
    }
}
