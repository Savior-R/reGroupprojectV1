package com.shijiangtai.m3classroom.api.error;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomain(
            DomainException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.code().httpStatus())
                .body(new ApiErrorResponse(
                        exception.code().name(),
                        exception.getMessage(),
                        exception.details(),
                        traceId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<Map<String, Object>> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.<String, Object>of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null
                                ? "参数校验失败" : error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(
                        ErrorCode.VALIDATION_ERROR.name(),
                        "参数校验失败",
                        Map.of("fields", fields),
                        traceId(request)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadTooLarge(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.MATERIAL_TOO_LARGE.httpStatus())
                .body(new ApiErrorResponse(
                        ErrorCode.MATERIAL_TOO_LARGE.name(),
                        "上传文件超过大小限制",
                        Map.of(),
                        traceId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnknown(
            Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(new ApiErrorResponse(
                        ErrorCode.INTERNAL_ERROR.name(),
                        "内部错误",
                        Map.of(),
                        traceId(request)));
    }

    private String traceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        return traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString() : traceId;
    }
}
