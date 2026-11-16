package com.shijiangtai.m5question.domain;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

/** Category and question lifecycle status (04 §3). */
public enum QuestionStatus {
    ACTIVE("active"),
    INACTIVE("inactive");

    private final String wireValue;

    QuestionStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static QuestionStatus fromWire(String value) {
        for (QuestionStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new DomainException(ErrorCode.VALIDATION_ERROR,
                "未知题库状态", Map.of("field", "status", "value", String.valueOf(value)));
    }
}
