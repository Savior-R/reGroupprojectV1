package com.shijiangtai.m4eval.infrastructure.web;

import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import com.shijiangtai.shared.infrastructure.web.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps M4 contract error codes before the shared catch-all handler (06 §9.4). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class M4ExceptionHandler {

    @ExceptionHandler(EvaluationException.class)
    public ResponseEntity<ApiErrorResponse> handleEvaluation(
            EvaluationException exception, HttpServletRequest request) {
        EvaluationErrorCode code = exception.code();
        return ResponseEntity.status(code.httpStatus())
                .body(new ApiErrorResponse(
                        code.name(),
                        exception.getMessage(),
                        exception.details(),
                        traceId(request)));
    }

    private static String traceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        return traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString() : traceId;
    }
}
