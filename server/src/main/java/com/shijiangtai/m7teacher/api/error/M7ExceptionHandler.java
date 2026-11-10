package com.shijiangtai.m7teacher.api.error;

import com.shijiangtai.m7teacher.domain.M7ErrorCode;
import com.shijiangtai.m7teacher.domain.M7Exception;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** M7 error advice; runs before the M3 catch-all advice. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class M7ExceptionHandler {

    @ExceptionHandler(M7Exception.class)
    public ResponseEntity<M7ApiErrorResponse> handleM7(
            M7Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.code().httpStatus())
                .body(new M7ApiErrorResponse(
                        exception.code().name(), exception.getMessage(),
                        exception.details(), traceId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<M7ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<Map<String, Object>> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.<String, Object>of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null
                                ? "参数校验失败" : error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(new M7ApiErrorResponse(M7ErrorCode.VALIDATION_ERROR.name(), "参数校验失败",
                        Map.of("fields", fields), traceId(request)));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<M7ApiErrorResponse> handleBadRequest(
            Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(new M7ApiErrorResponse(M7ErrorCode.VALIDATION_ERROR.name(), "请求参数格式错误",
                        Map.of(), traceId(request)));
    }

    private static String traceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        return traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString() : traceId;
    }
}
