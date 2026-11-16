package com.shijiangtai.m7teacher.api.error;

import java.util.Map;

/** Platform error body shape (02 §8.2). Serialized with snake_case by the global mapper. */
public record M7ApiErrorResponse(
        String code, String message, Map<String, Object> details, String traceId) {
}
