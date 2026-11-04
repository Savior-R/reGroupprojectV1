package com.shijiangtai.m3classroom.api.error;

import java.util.Map;

public record ApiErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        String traceId) {
}
