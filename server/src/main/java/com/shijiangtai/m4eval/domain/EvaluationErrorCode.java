package com.shijiangtai.m4eval.domain;

/**
 * M4 error codes (06 §9.4).
 *
 * <p>They live in the M4 package rather than the shared {@code ErrorCode} enum so the M4 module
 * can ship its contract codes without editing the Foundation-owned shared contract surface.
 */
public enum EvaluationErrorCode {
    EVALUATION_NOT_READY(409),
    EVALUATION_FAILED(422),
    EVALUATION_INPUT_INCOMPLETE(422),
    EVALUATION_TIMEOUT(503),
    EVALUATION_INVALID_RESULT(502),
    EVALUATION_ATTEMPT_STALE(409),
    EVALUATION_NOT_FOUND(404),
    EVALUATION_ALREADY_TERMINAL(409),
    AI_SERVICE_UNAVAILABLE(503),
    AI_TIMEOUT(503),
    AI_OUTPUT_INVALID(502);

    private final int httpStatus;

    EvaluationErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
