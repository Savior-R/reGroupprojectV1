package com.shijiangtai.m4eval.domain;

import java.util.Map;
import java.util.Objects;

/** M4 domain failure carrying one of the M4 contract error codes. */
public class EvaluationException extends RuntimeException {

    private final EvaluationErrorCode code;
    private final Map<String, Object> details;

    public EvaluationException(EvaluationErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public EvaluationException(
            EvaluationErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.details = Map.copyOf(details == null ? Map.of() : details);
    }

    public EvaluationErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
