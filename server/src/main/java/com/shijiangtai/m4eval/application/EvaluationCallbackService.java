package com.shijiangtai.m4eval.application;

import com.shijiangtai.m4eval.application.command.EvaluationFailCommand;
import com.shijiangtai.m4eval.application.command.EvaluationResultCommand;
import com.shijiangtai.m4eval.application.command.StartEvaluationCommand;
import com.shijiangtai.m4eval.application.port.DimensionScoreRepository;
import com.shijiangtai.m4eval.application.port.EvaluationIssueRepository;
import com.shijiangtai.m4eval.application.port.EvaluationJobRepository;
import com.shijiangtai.m4eval.application.port.EvaluationRepository;
import com.shijiangtai.m4eval.application.view.CallbackResultView;
import com.shijiangtai.m4eval.domain.DimensionCatalog;
import com.shijiangtai.m4eval.domain.DimensionScore;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import com.shijiangtai.m4eval.domain.EvaluationIssue;
import com.shijiangtai.m4eval.domain.EvaluationJob;
import com.shijiangtai.m4eval.domain.EvaluationJobStatus;
import com.shijiangtai.m4eval.domain.EvaluationOutcome;
import com.shijiangtai.m4eval.domain.EvaluationPolicy;
import com.shijiangtai.m4eval.domain.EvaluationResultValidator;
import com.shijiangtai.m4eval.domain.EvaluationStatus;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.EventPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * start / result / fail callbacks from the Python worker (06 §5.3, §5.7).
 *
 * <p>Idempotency is {@code (evaluation_id, attempt)}. A replay of the current attempt is ignored,
 * an older attempt returns 200 + ignored, a newer attempt is 409, and a terminal evaluation
 * rejects every further attempt.
 */
@Service
public class EvaluationCallbackService {

    private final EvaluationRepository evaluationRepository;
    private final EvaluationJobRepository jobRepository;
    private final DimensionScoreRepository dimensionScoreRepository;
    private final EvaluationIssueRepository issueRepository;
    private final EventPublisher eventPublisher;
    private final ClockProvider clock;

    public EvaluationCallbackService(
            EvaluationRepository evaluationRepository,
            EvaluationJobRepository jobRepository,
            DimensionScoreRepository dimensionScoreRepository,
            EvaluationIssueRepository issueRepository,
            EventPublisher eventPublisher,
            ClockProvider clock) {
        this.evaluationRepository = Objects.requireNonNull(
                evaluationRepository, "evaluationRepository");
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.dimensionScoreRepository = Objects.requireNonNull(
                dimensionScoreRepository, "dimensionScoreRepository");
        this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public CallbackResultView start(StartEvaluationCommand command) {
        Evaluation evaluation = requireEvaluation(command.evaluationId());
        EvaluationJob job = requireJob(evaluation, command.jobId());
        if (isIgnored(evaluation, job, command.attempt())) {
            return ignored(evaluation);
        }
        Instant now = clock.now();
        evaluation.markRunning(command.attempt(), now);
        job.markRunning(now);
        evaluationRepository.save(evaluation);
        jobRepository.save(job);
        return accepted(evaluation);
    }

    @Transactional
    public CallbackResultView complete(EvaluationResultCommand command) {
        Evaluation evaluation = requireEvaluation(command.evaluationId());
        EvaluationJob job = requireJob(evaluation, command.jobId());
        if (isIgnored(evaluation, job, command.attempt())) {
            return ignored(evaluation);
        }

        List<DimensionScore> dimensions = normalizeDimensions(evaluation, command.dimensions());
        List<EvaluationIssue> issues = normalizeIssues(evaluation, command.issues());
        EvaluationResultValidator.validate(evaluation.mode(), evaluation.weights(), dimensions,
                issues);

        Instant now = clock.now();
        EvaluationOutcome outcome = EvaluationPolicy.decide(
                evaluation.mode(),
                evaluation.weights(),
                EvaluationResultValidator.scoreMap(dimensions),
                command.warnings(),
                command.partialReasons(),
                command.modelVersion(),
                command.promptVersion() == null
                        ? "m4-eval-v1" : command.promptVersion(),
                command.rubricVersion() == null
                        ? DimensionCatalog.RUBRIC_VERSION : command.rubricVersion(),
                command.tokenUsage(),
                now);

        if (outcome.status() == EvaluationStatus.FAILED) {
            String message = "worker returned no dimension scores";
            dimensionScoreRepository.replaceAll(evaluation.id(), List.of());
            issueRepository.replaceAll(evaluation.id(), List.of());
            evaluation.fail(EvaluationErrorCode.EVALUATION_INVALID_RESULT.name(), message, now);
            job.markTerminal(EvaluationJobStatus.FAILED, message, now);
        } else {
            dimensionScoreRepository.replaceAll(evaluation.id(), dimensions);
            issueRepository.replaceAll(evaluation.id(), issues);
            evaluation.apply(outcome);
            job.markTerminal(jobStatus(outcome.status()), null, now);
        }
        evaluationRepository.save(evaluation);
        jobRepository.save(job);
        publishTerminal(evaluation, command.attempt());
        return accepted(evaluation);
    }

    @Transactional
    public CallbackResultView fail(EvaluationFailCommand command) {
        Evaluation evaluation = requireEvaluation(command.evaluationId());
        EvaluationJob job = requireJob(evaluation, command.jobId());
        if (isIgnored(evaluation, job, command.attempt())) {
            return ignored(evaluation);
        }

        Instant now = clock.now();
        List<DimensionScore> existing = dimensionScoreRepository.findByEvaluationId(
                evaluation.id());
        List<String> reasons = new ArrayList<>(command.partialReasons());
        if (reasons.isEmpty()) {
            reasons.add("DIMENSION_TIMEOUT");
        }
        if (existing.isEmpty()) {
            evaluation.fail(
                    command.errorCode() == null
                            ? EvaluationErrorCode.AI_SERVICE_UNAVAILABLE.name()
                            : command.errorCode(),
                    command.errorMessage(),
                    now);
            job.markTerminal(
                    EvaluationJobStatus.FAILED,
                    command.errorMessage(),
                    now);
        } else {
            evaluation.apply(new EvaluationOutcome(
                    EvaluationStatus.PARTIAL,
                    null,
                    evaluation.warnings(),
                    reasons,
                    null,
                    null,
                    DimensionCatalog.RUBRIC_VERSION,
                    Map.of(),
                    now));
            job.markTerminal(EvaluationJobStatus.PARTIAL, command.errorMessage(), now);
        }
        evaluationRepository.save(evaluation);
        jobRepository.save(job);
        publishTerminal(evaluation, command.attempt());
        return accepted(evaluation);
    }

    /**
     * Shared attempt guard used by start / result / fail.
     *
     * @return true when the callback must be acknowledged without writing anything
     * @throws EvaluationException 409 when the attempt is ahead of the issued attempt
     */
    private boolean isIgnored(Evaluation evaluation, EvaluationJob job, int attempt) {
        if (attempt > job.attempts()) {
            throw new EvaluationException(
                    EvaluationErrorCode.EVALUATION_ATTEMPT_STALE,
                    "attempt is newer than the issued attempt",
                    Map.of("issued_attempt", job.attempts(), "callback_attempt", attempt));
        }
        if (attempt < job.attempts()) {
            return true;
        }
        return evaluation.terminal() || job.terminal();
    }

    private Evaluation requireEvaluation(UUID evaluationId) {
        return evaluationRepository.findById(evaluationId)
                .orElseThrow(() -> new EvaluationException(
                        EvaluationErrorCode.EVALUATION_NOT_FOUND, "评测不存在"));
    }

    private EvaluationJob requireJob(Evaluation evaluation, UUID jobId) {
        EvaluationJob job = jobRepository
                .findById(jobId)
                .orElseThrow(() -> new EvaluationException(
                        EvaluationErrorCode.EVALUATION_NOT_FOUND, "评测任务不存在"));
        if (!job.evaluationId().equals(evaluation.id())) {
            throw new EvaluationException(
                    EvaluationErrorCode.EVALUATION_NOT_FOUND, "评测任务不属于该评测");
        }
        return job;
    }

    private static List<DimensionScore> normalizeDimensions(
            Evaluation evaluation, List<DimensionScore> dimensions) {
        return EvaluationResultValidator.normalize(dimensions.stream()
                .map(score -> new DimensionScore(
                        score.id() == null ? UUID.randomUUID() : score.id(),
                        evaluation.id(),
                        score.dimension(),
                        score.score(),
                        score.subScores(),
                        score.subConfidences(),
                        score.confidence()))
                .toList());
    }

    private static List<EvaluationIssue> normalizeIssues(
            Evaluation evaluation, List<EvaluationIssue> issues) {
        List<EvaluationIssue> ordered = new ArrayList<>(issues);
        List<String> axisOrder = DimensionCatalog.applicableDimensions(evaluation.mode());
        ordered.sort(Comparator
                .comparingInt((EvaluationIssue issue) -> axisOrder.indexOf(issue.dimension()))
                .thenComparing(issue -> issue.startMs() == null
                        ? Long.MAX_VALUE : issue.startMs())
                .thenComparing(issue -> issue.endMs() == null
                        ? Long.MAX_VALUE : issue.endMs()));
        List<EvaluationIssue> normalized = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            EvaluationIssue issue = ordered.get(index);
            normalized.add(new EvaluationIssue(
                    issue.id() == null ? UUID.randomUUID() : issue.id(),
                    evaluation.id(),
                    issue.dimension(),
                    issue.startMs(),
                    issue.endMs(),
                    issue.severity(),
                    issue.evidence(),
                    issue.suggestion(),
                    issue.isSuggestion(),
                    issue.suggestionRank(),
                    index));
        }
        return normalized;
    }

    private void publishTerminal(Evaluation evaluation, int attempt) {
        // EventPublisher defers to after-commit, so M7 never sees an uncommitted terminal.
        eventPublisher.publish(new com.shijiangtai.m4eval.domain.EvaluationTerminalEvent(
                evaluation.id(),
                evaluation.sourceType(),
                evaluation.sourceId(),
                evaluation.sessionId(),
                evaluation.status(),
                evaluation.completedAt(),
                attempt));
    }

    private static EvaluationJobStatus jobStatus(EvaluationStatus status) {
        return switch (status) {
            case COMPLETED -> EvaluationJobStatus.COMPLETED;
            case PARTIAL -> EvaluationJobStatus.PARTIAL;
            case FAILED -> EvaluationJobStatus.FAILED;
            default -> throw new IllegalArgumentException("terminal status required: " + status);
        };
    }

    private static CallbackResultView accepted(Evaluation evaluation) {
        return new CallbackResultView(
                evaluation.id().toString(), evaluation.status().wireValue(), false);
    }

    private static CallbackResultView ignored(Evaluation evaluation) {
        return new CallbackResultView(
                evaluation.id().toString(), evaluation.status().wireValue(), true);
    }
}
