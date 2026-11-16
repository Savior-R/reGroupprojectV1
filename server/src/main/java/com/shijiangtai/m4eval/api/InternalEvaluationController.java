package com.shijiangtai.m4eval.api;

import com.shijiangtai.m4eval.api.dto.FailCallbackRequest;
import com.shijiangtai.m4eval.api.dto.ResultCallbackRequest;
import com.shijiangtai.m4eval.api.dto.StartCallbackRequest;
import com.shijiangtai.m4eval.application.EvaluationCallbackService;
import com.shijiangtai.m4eval.application.EvaluationInputService;
import com.shijiangtai.m4eval.application.command.EvaluationFailCommand;
import com.shijiangtai.m4eval.application.command.EvaluationResultCommand;
import com.shijiangtai.m4eval.application.command.StartEvaluationCommand;
import com.shijiangtai.m4eval.application.view.CallbackResultView;
import com.shijiangtai.m4eval.application.view.EvaluationInputManifestView;
import com.shijiangtai.m4eval.application.view.InputEventView;
import com.shijiangtai.m4eval.application.view.InputFrameView;
import com.shijiangtai.m4eval.application.view.InputTextView;
import com.shijiangtai.m4eval.domain.DimensionScore;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import com.shijiangtai.m4eval.domain.EvaluationIssue;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.PageResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal endpoints for the Python evaluation worker (06 §5.3, §9.3). */
@RestController
@RequestMapping("/internal/evaluations")
public class InternalEvaluationController {

    private final EvaluationCallbackService callbackService;
    private final EvaluationInputService inputService;
    private final IdGenerator idGenerator;

    public InternalEvaluationController(
            EvaluationCallbackService callbackService,
            EvaluationInputService inputService,
            IdGenerator idGenerator) {
        this.callbackService = callbackService;
        this.inputService = inputService;
        this.idGenerator = idGenerator;
    }

    @PostMapping("/{evaluationId}/start")
    public CallbackResultView start(
            @PathVariable UUID evaluationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody StartCallbackRequest request) {
        verifyIdempotencyKey(idempotencyKey, request.jobId(), request.attempt());
        return callbackService.start(new StartEvaluationCommand(
                evaluationId, requireJobId(request.jobId()), requireAttempt(request.attempt())));
    }

    @PostMapping("/{evaluationId}/result")
    public CallbackResultView result(
            @PathVariable UUID evaluationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ResultCallbackRequest request) {
        verifyIdempotencyKey(idempotencyKey, request.jobId(), request.attempt());
        List<DimensionScore> dimensions = request.dimensions() == null
                ? List.of()
                : request.dimensions().stream()
                        .map(payload -> toDimensionScore(evaluationId, payload))
                        .toList();
        List<EvaluationIssue> issues = request.issues() == null
                ? List.of()
                : request.issues().stream()
                        .map(payload -> toIssue(evaluationId, payload))
                        .toList();
        return callbackService.complete(new EvaluationResultCommand(
                evaluationId,
                requireJobId(request.jobId()),
                requireAttempt(request.attempt()),
                request.status(),
                request.modelVersion(),
                request.promptVersion(),
                request.rubricVersion(),
                dimensions,
                issues,
                request.warnings(),
                request.partialReasons(),
                request.inputFlags(),
                request.tokenUsage()));
    }

    @PostMapping("/{evaluationId}/fail")
    public CallbackResultView fail(
            @PathVariable UUID evaluationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody FailCallbackRequest request) {
        verifyIdempotencyKey(idempotencyKey, request.jobId(), request.attempt());
        return callbackService.fail(new EvaluationFailCommand(
                evaluationId,
                requireJobId(request.jobId()),
                requireAttempt(request.attempt()),
                request.errorCode(),
                request.errorMessage(),
                request.partialReasons()));
    }

    @GetMapping("/{evaluationId}/input/manifest")
    public EvaluationInputManifestView manifest(@PathVariable UUID evaluationId) {
        return inputService.manifest(evaluationId);
    }

    @GetMapping("/{evaluationId}/input/texts")
    public PageResult<InputTextView> texts(
            @PathVariable UUID evaluationId,
            @RequestParam(name = "after_id", required = false) UUID afterId,
            @RequestParam(name = "limit", defaultValue = "500") int limit) {
        return inputService.texts(evaluationId, afterId, limit);
    }

    @GetMapping("/{evaluationId}/input/frames")
    public PageResult<InputFrameView> frames(
            @PathVariable UUID evaluationId,
            @RequestParam(name = "after_ts", required = false) Long afterTs,
            @RequestParam(name = "after_id", required = false) UUID afterId,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        return inputService.frames(evaluationId, afterTs, afterId, limit);
    }

    @GetMapping("/{evaluationId}/input/events")
    public PageResult<InputEventView> events(
            @PathVariable UUID evaluationId,
            @RequestParam(name = "after_seq", required = false) Long afterSeq,
            @RequestParam(name = "limit", defaultValue = "500") int limit) {
        return inputService.events(evaluationId, afterSeq, limit);
    }

    private DimensionScore toDimensionScore(
            UUID evaluationId, ResultCallbackRequest.DimensionPayload payload) {
        if (payload.dimension() == null || payload.dimension().isBlank()) {
            throw badRequest("dimension is required");
        }
        if (payload.score() == null || payload.confidence() == null) {
            throw badRequest("dimension score and confidence are required");
        }
        return new DimensionScore(
                idGenerator.nextId(),
                evaluationId,
                payload.dimension(),
                payload.score(),
                payload.subScores() == null ? Map.of() : payload.subScores(),
                payload.subConfidences() == null ? Map.of() : payload.subConfidences(),
                payload.confidence());
    }

    private EvaluationIssue toIssue(
            UUID evaluationId, ResultCallbackRequest.IssuePayload payload) {
        if (payload.dimension() == null || payload.dimension().isBlank()) {
            throw badRequest("issue dimension is required");
        }
        if (payload.severity() == null || payload.severity().isBlank()) {
            throw badRequest("issue severity is required");
        }
        return new EvaluationIssue(
                idGenerator.nextId(),
                evaluationId,
                payload.dimension(),
                payload.startMs(),
                payload.endMs(),
                payload.severity(),
                payload.evidence() == null ? "" : payload.evidence(),
                payload.suggestion() == null ? "" : payload.suggestion(),
                Boolean.TRUE.equals(payload.isSuggestion()),
                payload.suggestionRank(),
                0);
    }

    private static UUID requireJobId(UUID jobId) {
        if (jobId == null) {
            throw badRequest("job_id is required");
        }
        return jobId;
    }

    private static int requireAttempt(Integer attempt) {
        if (attempt == null || attempt < 1) {
            throw badRequest("attempt must be a positive integer");
        }
        return attempt;
    }

    private static void verifyIdempotencyKey(
            String idempotencyKey, UUID jobId, Integer attempt) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        String expected = jobId + ":" + attempt;
        if (!expected.equals(idempotencyKey.trim())) {
            throw badRequest("Idempotency-Key does not match job_id:attempt");
        }
    }

    private static EvaluationException badRequest(String message) {
        return new EvaluationException(
                EvaluationErrorCode.EVALUATION_INVALID_RESULT, message, Map.of());
    }
}
