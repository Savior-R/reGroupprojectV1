package com.shijiangtai.shared.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DomainException extends RuntimeException {
    private final ErrorCode code;
    private final Map<String, Object> details;

    public DomainException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public DomainException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.details = Map.copyOf(details == null ? Map.of() : details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static DomainException fields(ErrorCode code, String message, List<Map<String, Object>> fields) {
        return new DomainException(code, message, Map.of("fields", List.copyOf(fields)));
    }
}
