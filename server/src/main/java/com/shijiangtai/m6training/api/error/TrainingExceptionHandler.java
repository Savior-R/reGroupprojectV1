package com.shijiangtai.m6training.api.error;

import com.shijiangtai.m6training.domain.TrainingException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps module-local M6 errors. Highest precedence so the M3 catch-all advice cannot swallow
 * {@link TrainingException} as a generic exception.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrainingExceptionHandler {

    @ExceptionHandler(TrainingException.class)
    public ResponseEntity<TrainingErrorResponse> handle(
            TrainingException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.error().httpStatus())
                .body(new TrainingErrorResponse(
                        exception.error().name(),
                        exception.getMessage(),
                        exception.details(),
                        traceId(request)));
    }

    private String traceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        return traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString() : traceId;
    }

    public record TrainingErrorResponse(
            String code, String message, Map<String, Object> details, String traceId) {
    }
}
