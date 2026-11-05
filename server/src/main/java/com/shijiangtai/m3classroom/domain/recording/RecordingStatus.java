package com.shijiangtai.m3classroom.domain.recording;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

public enum RecordingStatus {
    RECORDING("recording"),
    UPLOADING("uploading"),
    UPLOADED("uploaded"),
    FAILED("failed"),
    DELETED("deleted");

    private final String wireValue;

    RecordingStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static RecordingStatus fromWire(String value) {
        for (RecordingStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new DomainException(ErrorCode.VALIDATION_ERROR,
                "未知录制状态",
                Map.of("field", "recording_status", "value", String.valueOf(value)));
    }
}
