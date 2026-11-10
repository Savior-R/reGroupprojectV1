package com.shijiangtai.m6training.domain;

/**
 * M6 error codes (08 §10.7).
 *
 * <p>Module-local because {@code shared.domain.ErrorCode} is owned by the architecture owner;
 * a later contract pass can fold these into the shared enum.
 */
public enum TrainingError {
    TRAINING_SOURCE_INVALID(422),
    TRAINING_NOT_ELIGIBLE(422),
    TRAINING_TEMPLATE_EXISTS(409),
    TRAINING_TEMPLATE_UNAVAILABLE(422),
    TRAINING_SESSION_ACTIVE(409),
    TRAINING_CONFIG_IMMUTABLE(422),
    TRAINING_MATERIAL_INVALID(422),
    TRAINING_RECORD_LIMIT(422),
    TRAINING_RECORD_EXPIRED(404),
    DUPLICATE_RESOURCE(409),
    EVALUATION_NOT_READY(409),
    EVALUATION_FAILED(422),
    RECORDING_INCOMPLETE(422),
    MATERIAL_PARSE_FAILED(422),
    MATERIAL_TOO_LARGE(422),
    MATERIAL_TYPE_UNSUPPORTED(422),
    ROLE_NOT_ALLOWED(403),
    FORBIDDEN(403),
    CROSS_SCHOOL_ACCESS(403),
    RESOURCE_NOT_FOUND(404),
    VALIDATION_ERROR(400),
    INTERNAL_ERROR(500),
    SERVICE_UNAVAILABLE(503);

    private final int httpStatus;

    TrainingError(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
