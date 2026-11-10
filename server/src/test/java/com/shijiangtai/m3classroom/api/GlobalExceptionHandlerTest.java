package com.shijiangtai.m3classroom.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.shared.infrastructure.web.ApiErrorResponse;
import com.shijiangtai.shared.infrastructure.web.GlobalExceptionHandler;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void domainExceptionUsesCodeAndHttpStatus() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-123");

        ResponseEntity<ApiErrorResponse> response = handler.handleDomain(
                new DomainException(ErrorCode.TASK_NOT_DRAFT, "任务不是草稿"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("TASK_NOT_DRAFT");
        assertThat(response.getBody().traceId()).isEqualTo("trace-123");
    }

    @Test
    void unknownExceptionReturnsInternalErrorWithoutStackTrace() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-456");

        ResponseEntity<ApiErrorResponse> response = handler.handleUnknown(
                new RuntimeException("secret detail"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("内部错误");
        assertThat(response.getBody().message()).doesNotContain("secret detail");
        assertThat(response.getBody().traceId()).isEqualTo("trace-456");
    }
}
