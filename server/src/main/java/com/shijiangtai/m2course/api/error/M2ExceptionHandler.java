package com.shijiangtai.m2course.api.error;

import com.shijiangtai.shared.domain.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * M2 补充异常映射：路径 / 查询参数类型不合法（例如 course_id 不是 UUID）返回 400 而不是 500。
 *
 * <p>只处理这一个异常类型，其余异常仍由 M3 的 {@code GlobalExceptionHandler} 统一映射，
 * 避免两个 advice 争抢同一异常。
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

    private String traceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }

    /** 与 docs/design/02 §8.1 的错误响应体保持一致。 */
    public record M2ErrorResponse(
            String code, String message, Map<String, Object> details, String traceId) {
    }
}
