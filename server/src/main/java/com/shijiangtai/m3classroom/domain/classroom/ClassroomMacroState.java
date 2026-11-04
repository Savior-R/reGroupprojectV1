package com.shijiangtai.m3classroom.domain.classroom;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

/** Top-level classroom state: lecturing, question-and-answer, ended. */
public enum ClassroomMacroState {
    LECTURING("lecturing"),
    QA("qa"),
    ENDED("ended");

    private final String wireValue;

    ClassroomMacroState(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static ClassroomMacroState fromWire(String value) {
        for (ClassroomMacroState state : values()) {
            if (state.wireValue.equals(value)) {
                return state;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                "未知课堂宏观状态",
                Map.of("field", "state", "value", String.valueOf(value)));
    }
}
