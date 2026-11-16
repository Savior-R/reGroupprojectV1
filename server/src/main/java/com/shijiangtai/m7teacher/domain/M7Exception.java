package com.shijiangtai.m7teacher.domain;

import java.util.Map;
import java.util.Objects;

/** Business failure carrying an M7 error code and optional details. */
public class M7Exception extends RuntimeException {

    private final M7ErrorCode code;
    private final Map<String, Object> details;

    public M7Exception(M7ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public M7Exception(M7ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.details = Map.copyOf(details == null ? Map.of() : details);
    }

    public M7ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
