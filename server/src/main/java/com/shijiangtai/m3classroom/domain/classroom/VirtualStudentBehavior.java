package com.shijiangtai.m3classroom.domain.classroom;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

/** Behaviour state of a single virtual student (05A §5.2). */
public enum VirtualStudentBehavior {
    FOCUSED("focused"),
    DISTRACTED("distracted"),
    WHISPERING("whispering"),
    HAND_RAISED("hand_raised"),
    CALLED_ON("called_on"),
    ANSWERING("answering"),
    DIALOGUE("dialogue");

    private final String wireValue;

    VirtualStudentBehavior(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static VirtualStudentBehavior fromWire(String value) {
        for (VirtualStudentBehavior behavior : values()) {
            if (behavior.wireValue.equals(value)) {
                return behavior;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                "未知虚拟学生状态",
                Map.of("field", "student_state", "value", String.valueOf(value)));
    }
}
