package com.shijiangtai.m7teacher.domain;

/**
 * M7 error codes (07 §11.7 / 02 §8.2).
 *
 * <p>The shared {@code ErrorCode} enum belongs to the Foundation Owner, so M7 keeps its own
 * error catalogue and advice. HTTP status and the wire body shape stay identical to the
 * platform contract: {@code {code, message, details, trace_id}}.
 */
public enum M7ErrorCode {
    VALIDATION_ERROR(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    CROSS_SCHOOL_ACCESS(403),
    TEACHER_NOT_IN_GROUP(403),
    RESOURCE_NOT_FOUND(404),
    GRADING_CONFIG_INVALID(422),
    GRADING_CONFIG_LOCKED(409),
    GRADING_CONFIG_VERSION_CONFLICT(409),
    TEACHER_SCORE_INVALID(422),
    TEACHER_SCORE_CONFLICT(409),
    SUBMISSION_NOT_SUBMITTED(422),
    AI_SCORE_NOT_READY(409),
    DATA_INCONSISTENT(422),
    EXPORT_TOO_LARGE(422),
    COURSE_ARCHIVED(422),
    TASK_NOT_DRAFT(422),
    INTERNAL_ERROR(500),
    SERVICE_UNAVAILABLE(503);

    private final int httpStatus;

    M7ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
