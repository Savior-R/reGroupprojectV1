package com.shijiangtai.m4eval.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shijiangtai.m4eval.api.dto.FailCallbackRequest;
import com.shijiangtai.m4eval.api.dto.ResultCallbackRequest;
import com.shijiangtai.m4eval.api.dto.StartCallbackRequest;
import com.shijiangtai.m4eval.application.EvaluationCallbackService;
import com.shijiangtai.m4eval.application.EvaluationInputService;
import com.shijiangtai.m4eval.application.command.EvaluationFailCommand;
import com.shijiangtai.m4eval.application.command.EvaluationResultCommand;
import com.shijiangtai.m4eval.application.command.StartEvaluationCommand;
import com.shijiangtai.m4eval.application.view.CallbackResultView;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import com.shijiangtai.shared.application.IdGenerator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Callback DTO conversion and idempotency-key guard (M4-T03 / G6 contract). */
class InternalEvaluationControllerTest {

    private static final UUID EVALUATION_ID =
            UUID.fromString("11111111-aaaa-aaaa-aaaa-111111111111");
    private static final UUID JOB_ID =
            UUID.fromString("22222222-bbbb-bbbb-bbbb-222222222222");

    private EvaluationCallbackService callbackService;
    private InternalEvaluationController controller;

    @BeforeEach
    void setUp() {
        callbackService = mock(EvaluationCallbackService.class);
        EvaluationInputService inputService = mock(EvaluationInputService.class);
        IdGenerator ids = () -> UUID.fromString("33333333-cccc-cccc-cccc-333333333333");
        controller = new InternalEvaluationController(callbackService, inputService, ids);
        when(callbackService.start(any())).thenReturn(
                new CallbackResultView(EVALUATION_ID.toString(), "running", false));
        when(callbackService.complete(any())).thenReturn(
                new CallbackResultView(EVALUATION_ID.toString(), "completed", false));
        when(callbackService.fail(any())).thenReturn(
                new CallbackResultView(EVALUATION_ID.toString(), "failed", false));
    }

    @Test
    void startVerifiesIdempotencyKeyAndPassesTheIssuedAttempt() {
        var result = controller.start(
                EVALUATION_ID,
                JOB_ID + ":2",
                new StartCallbackRequest(JOB_ID, 2));

        assertThat(result.status()).isEqualTo("running");
        ArgumentCaptor<StartEvaluationCommand> captor =
                ArgumentCaptor.forClass(StartEvaluationCommand.class);
        verify(callbackService).start(captor.capture());
        assertThat(captor.getValue().evaluationId()).isEqualTo(EVALUATION_ID);
        assertThat(captor.getValue().jobId()).isEqualTo(JOB_ID);
        assertThat(captor.getValue().attempt()).isEqualTo(2);
    }

    @Test
    void missingIdempotencyKeyIsToleratedButMismatchIsRejected() {
        controller.start(EVALUATION_ID, null, new StartCallbackRequest(JOB_ID, 1));

        assertThatThrownBy(() -> controller.start(
                EVALUATION_ID, "other:1", new StartCallbackRequest(JOB_ID, 1)))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_INVALID_RESULT));
    }

    @Test
    void missingJobOrAttemptIsRejectedBeforeCallingTheService() {
        assertThatThrownBy(() -> controller.start(
                EVALUATION_ID, null, new StartCallbackRequest(null, 1)))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_INVALID_RESULT));
        assertThatThrownBy(() -> controller.start(
                EVALUATION_ID, null, new StartCallbackRequest(JOB_ID, null)))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_INVALID_RESULT));
        verifyNoInteractions(callbackService);
    }

    @Test
    void resultMapsSnakeCasePayloadIntoDimensionScoresAndIssues() {
        ResultCallbackRequest request = new ResultCallbackRequest(
                JOB_ID,
                1,
                "completed",
                "mock-model",
                "m4-eval-v1",
                "m4-rubric-v1",
                List.of(new ResultCallbackRequest.DimensionPayload(
                        "content",
                        new BigDecimal("82.5"),
                        Map.of("accuracy", new BigDecimal("80.0")),
                        Map.of("accuracy", new BigDecimal("0.8")),
                        new BigDecimal("0.8"))),
                List.of(new ResultCallbackRequest.IssuePayload(
                        "content", 1000L, 2000L, "medium", "证据", "建议", true, 1)),
                List.of("EMPTY_EVENTS"),
                List.of(),
                Map.of("transcript_complete", true),
                Map.of("prompt", 10, "completion", 5));

        controller.result(EVALUATION_ID, JOB_ID + ":1", request);

        ArgumentCaptor<EvaluationResultCommand> captor =
                ArgumentCaptor.forClass(EvaluationResultCommand.class);
        verify(callbackService).complete(captor.capture());
        EvaluationResultCommand command = captor.getValue();
        assertThat(command.evaluationId()).isEqualTo(EVALUATION_ID);
        assertThat(command.jobId()).isEqualTo(JOB_ID);
        assertThat(command.attempt()).isEqualTo(1);
        assertThat(command.modelVersion()).isEqualTo("mock-model");
        assertThat(command.dimensions()).hasSize(1);
        assertThat(command.dimensions().get(0).evaluationId()).isEqualTo(EVALUATION_ID);
        assertThat(command.dimensions().get(0).dimension()).isEqualTo("content");
        assertThat(command.dimensions().get(0).score()).isEqualByComparingTo("82.5");
        assertThat(command.issues()).hasSize(1);
        assertThat(command.issues().get(0).isSuggestion()).isTrue();
        assertThat(command.warnings()).containsExactly("EMPTY_EVENTS");
        assertThat(command.tokenUsage()).containsEntry("prompt", 10);
    }

    @Test
    void resultWithoutDimensionsIsPassedAsAnEmptyList() {
        ResultCallbackRequest request = new ResultCallbackRequest(
                JOB_ID, 1, "failed", "mock-model", "m4-eval-v1", "m4-rubric-v1",
                null, null, null, List.of("DIMENSION_TIMEOUT"), null, null);

        controller.result(EVALUATION_ID, null, request);

        ArgumentCaptor<EvaluationResultCommand> captor =
                ArgumentCaptor.forClass(EvaluationResultCommand.class);
        verify(callbackService).complete(captor.capture());
        assertThat(captor.getValue().dimensions()).isEmpty();
        assertThat(captor.getValue().issues()).isEmpty();
        assertThat(captor.getValue().partialReasons()).containsExactly("DIMENSION_TIMEOUT");
    }

    @Test
    void dimensionWithoutScoreOrConfidenceIsRejected() {
        ResultCallbackRequest request = new ResultCallbackRequest(
                JOB_ID, 1, "completed", "mock", "v", "r",
                List.of(new ResultCallbackRequest.DimensionPayload(
                        "content", null, Map.of(), Map.of(), new BigDecimal("0.8"))),
                List.of(), List.of(), List.of(), Map.of(), Map.of());

        assertThatThrownBy(() -> controller.result(EVALUATION_ID, null, request))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_INVALID_RESULT));
        verifyNoInteractions(callbackService);
    }

    @Test
    void failPassesErrorAndReasons() {
        controller.fail(
                EVALUATION_ID,
                JOB_ID + ":1",
                new FailCallbackRequest(
                        JOB_ID, 1, "AI_TIMEOUT", "timeout", List.of("DIMENSION_TIMEOUT")));

        ArgumentCaptor<EvaluationFailCommand> captor =
                ArgumentCaptor.forClass(EvaluationFailCommand.class);
        verify(callbackService).fail(captor.capture());
        assertThat(captor.getValue().errorCode()).isEqualTo("AI_TIMEOUT");
        assertThat(captor.getValue().errorMessage()).isEqualTo("timeout");
        assertThat(captor.getValue().partialReasons()).containsExactly("DIMENSION_TIMEOUT");
    }
}
