package com.shijiangtai.m3classroom.domain.classroom;

/**
 * Teacher utterance intents used by the M3 dialogue state machine (05A §5.5).
 *
 * <p>The classifier is intentionally multi-label: one utterance may contain a comment and a
 * sit-down instruction ({@code 很好，请坐}) or a discipline action and a call-on
 * ({@code 张三别说话，李四你来说}). Java resolves the intents against the current classroom
 * state; the classifier itself never mutates the session.
 */
public enum TeacherIntent {
    ASK_CLASS("ask_class"),
    ASK_STUDENT("ask_student"),
    CALL_ON("call_on"),
    PROMPT_STUDENT_QUESTION("prompt_student_question"),
    MANAGE_DISCIPLINE("manage_discipline"),
    COMMENT_ANSWER("comment_answer"),
    END_DIALOGUE("end_dialogue"),
    END_QA("end_qa"),
    OTHER("other");

    private final String wireValue;

    TeacherIntent(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TeacherIntent fromWire(String value) {
        for (TeacherIntent intent : values()) {
            if (intent.wireValue.equals(value)) {
                return intent;
            }
        }
        throw new IllegalArgumentException("unknown teacher intent: " + value);
    }
}
