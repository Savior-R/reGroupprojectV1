package com.shijiangtai.shared.infrastructure.web;

import java.util.Map;

/** 统一错误响应体（02 §8.1）。 */
public record ApiErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        String traceId) {
}
