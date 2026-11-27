package com.shijiangtai.m6training.domain;

/** Why a course publication was taken down (08 §3.2). */
public enum TrainingUnpublishReason {
    MANUAL("manual"),
    COURSE_ARCHIVED("course_archived"),
    TEACHER_REMOVED("teacher_removed"),
    TEMPLATE_DELETED("template_deleted");

    private final String wireValue;

    TrainingUnpublishReason(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TrainingUnpublishReason fromWire(String value) {
        for (TrainingUnpublishReason reason : values()) {
            if (reason.wireValue.equals(value)) {
                return reason;
            }
        }
        throw new TrainingException(TrainingError.INTERNAL_ERROR,
                "未知下架原因 " + value);
    }
}
