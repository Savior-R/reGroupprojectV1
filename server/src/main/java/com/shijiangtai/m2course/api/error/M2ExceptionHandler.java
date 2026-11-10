package com.shijiangtai.m2course.api.error;

import com.shijiangtai.shared.domain.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;

/**
 * M2 补充异常映射：参数格式不合法返回 400 而不是 500。
 *
 * <p>覆盖两类：路径 / 查询参数类型不匹配（例如 course_id 不是 UUID），以及请求体字段无法反序列化
 * （例如 {@code major_ids: ["打打打"]} 不是 UUID）。二者都是「请求非法」而不是「服务内部错误」，
 * 落到 {@code GlobalExceptionHandler} 的兜底分支会错误地返回 500。
 *
 * <p>其余异常仍由 {@code GlobalExceptionHandler} 统一映射，避免两个 advice 争抢同一异常。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class M2ExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<M2ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.httpStatus())
                .body(new M2ErrorResponse(
                        ErrorCode.VALIDATION_ERROR.name(),
                        "参数格式非法：" + exception.getName(),
                        Map.of("parameter", exception.getName()),
                        traceId(request)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<M2ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        String field = fieldPath(exception);
        String message = field == null ? "请求体格式非法" : "请求体字段格式非法：" + field;
        Map<String, Object> details = field == null ? Map.of() : Map.of("field", field);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.httpStatus())
                .body(new M2ErrorResponse(
                        ErrorCode.VALIDATION_ERROR.name(), message, details, traceId(request)));
    }

    /**
     * 从 Jackson（Spring Boot 4 用的是 Jackson 3 的 {@code tools.jackson}）异常里取字段路径，
     * 例如 {@code major_ids[0]}；取不到则返回 null。
     */
    private static String fieldPath(HttpMessageNotReadableException exception) {
        Throwable cause = exception.getCause();
        for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
                return format(jackson.getPath());
            }
        }
        return null;
    }

    private static String format(List<JacksonException.Reference> references) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : references) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? null : path.toString();
    }

    private String traceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }

    /** 与 docs/design/02 §8.1 的错误响应体保持一致。 */
    public record M2ErrorResponse(
            String code, String message, Map<String, Object> details, String traceId) {
    }
}
