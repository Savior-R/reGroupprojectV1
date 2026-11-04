package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

public enum RecordingState {
    ACTIVE("active"),
    LOST("lost");

    private final String wireValue;

    RecordingState(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static RecordingState fromWire(String value) {
        for (RecordingState state : values()) {
            if (state.wireValue.equals(value)) {
                return state;
            }
        }
        throw new DomainException(ErrorCode.VALIDATION_ERROR,
                "未知录制状态",
                Map.of("field", "recording_state", "value", String.valueOf(value)));
    }
}
