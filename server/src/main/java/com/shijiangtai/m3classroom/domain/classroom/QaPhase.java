package com.shijiangtai.m3classroom.domain.classroom;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

/** Fine-grained phase inside the {@link ClassroomMacroState#QA} macro state. */
public enum QaPhase {
    WAITING_HANDS("waiting_hands"),
    STUDENT_ANSWERING("student_answering"),
    DIALOGUE("dialogue"),
    TEACHER_ANSWERING("teacher_answering");

    private final String wireValue;

    QaPhase(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static QaPhase fromWire(String value) {
        for (QaPhase phase : values()) {
            if (phase.wireValue.equals(value)) {
                return phase;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                "未知问答阶段",
                Map.of("field", "qa_phase", "value", String.valueOf(value)));
    }
}
