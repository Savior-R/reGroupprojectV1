package com.shijiangtai.m3classroom.domain.classroom;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

/** Answer correctness as reported by {@code teacher.mark_answer} (02 §6.4). */
public enum Correctness {
    CORRECT("correct"),
    PARTIAL("partial"),
    WRONG("wrong");

    private final String wireValue;

    Correctness(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Correctness fromWire(String value) {
        for (Correctness correctness : values()) {
            if (correctness.wireValue.equals(value)) {
                return correctness;
            }
        }
        throw new DomainException(ErrorCode.VALIDATION_ERROR,
                "未知回答正确性",
                Map.of("field", "correctness", "value", String.valueOf(value)));
    }
}
