package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

public enum FailureReason {
    GRACE_TIMEOUT("grace_timeout"),
    RECORDING_LOST("recording_lost"),
    TRACK_ENDED("track_ended"),
    WS_ERROR("ws_error"),
    INTERNAL("internal");

    private final String wireValue;

    FailureReason(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static FailureReason fromWire(String value) {
        for (FailureReason reason : values()) {
            if (reason.wireValue.equals(value)) {
                return reason;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                "未知失败原因",
                Map.of("field", "reason", "value", String.valueOf(value)));
    }
}
