package com.shijiangtai.m4eval.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** M4 contract error codes win over the shared catch-all handler (06 §9.4). */
class M4ExceptionHandlerTest {

    private final M4ExceptionHandler handler = new M4ExceptionHandler();

    @Test
    void mapsEachContractCodeToItsHttpStatusAndEchoesTraceId() {
        Map<EvaluationErrorCode, Integer> expected = Map.of(
                EvaluationErrorCode.EVALUATION_NOT_FOUND, 404,
                EvaluationErrorCode.EVALUATION_NOT_READY, 409,
                EvaluationErrorCode.EVALUATION_ATTEMPT_STALE, 409,
                EvaluationErrorCode.EVALUATION_FAILED, 422,
                EvaluationErrorCode.EVALUATION_INVALID_RESULT, 502,
                EvaluationErrorCode.EVALUATION_TIMEOUT, 503);

        expected.forEach((code, status) -> {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Trace-Id", "trace-1");

            var response = handler.handleEvaluation(
                    new EvaluationException(code, "message", Map.of("k", "v")), request);

            assertThat(response.getStatusCode().value()).isEqualTo(status);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo(code.name());
            assertThat(response.getBody().message()).isEqualTo("message");
            assertThat(response.getBody().details()).containsEntry("k", "v");
            assertThat(response.getBody().traceId()).isEqualTo("trace-1");
        });
    }

    @Test
    void generatesATraceIdWhenTheHeaderIsMissing() {
        var response = handler.handleEvaluation(
                new EvaluationException(EvaluationErrorCode.EVALUATION_FAILED, "failed"),
                new MockHttpServletRequest());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().traceId()).isNotBlank();
    }
}
