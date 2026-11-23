package com.shijiangtai.m4eval.application;

import static com.shijiangtai.m4eval.application.M4TestDoubles.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryDimensions;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryEvaluations;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryIssues;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryJobs;
import com.shijiangtai.m4eval.application.M4TestDoubles.MutableClock;
import com.shijiangtai.m4eval.application.M4TestDoubles.RecordingEvents;
import com.shijiangtai.m4eval.application.command.EvaluationFailCommand;
import com.shijiangtai.m4eval.application.command.EvaluationResultCommand;
import com.shijiangtai.m4eval.application.command.StartEvaluationCommand;
import com.shijiangtai.m4eval.domain.DimensionScore;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import com.shijiangtai.m4eval.domain.EvaluationIssue;
import com.shijiangtai.m4eval.domain.EvaluationJob;
import com.shijiangtai.m4eval.domain.EvaluationJobStatus;
import com.shijiangtai.m4eval.domain.EvaluationStatus;
import com.shijiangtai.m4eval.domain.EvaluationTerminalEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** start / result / fail callbacks and attempt idempotency (M4-T03 / T05 / T11). */
class EvaluationCallbackServiceTest {

    private static final UUID EVALUATION_ID =
            UUID.fromString("11111111-aaaa-aaaa-aaaa-111111111111");
    private static final UUID JOB_ID =
            UUID.fromString("22222222-bbbb-bbbb-bbbb-222222222222");
    private static final UUID SUBMISSION_ID =
            UUID.fromString("33333333-cccc-cccc-cccc-333333333333");

    private InMemoryEvaluations evaluations;
    private InMemoryJobs jobs;
    private InMemoryDimensions dimensions;
    private InMemoryIssues issues;
    private RecordingEvents events;
    private MutableClock clock;
    private EvaluationCallbackService service;

    @BeforeEach
    void setUp() {
        evaluations = new InMemoryEvaluations();
        jobs = new InMemoryJobs();
        dimensions = new InMemoryDimensions();
        issues = new InMemoryIssues();
        events = new RecordingEvents();
        clock = new MutableClock(NOW);
        service = new EvaluationCallbackService(
                evaluations, jobs, dimensions, issues, events, clock);
        seedPending();
    }

    private void seedPending() {
        evaluations.save(M4TestDoubles.pendingSubmission(EVALUATION_ID, SUBMISSION_ID));
        jobs.save(M4TestDoubles.queuedJob(JOB_ID, EVALUATION_ID));
    }

    private List<DimensionScore> allDimensions() {
        return List.of(
                M4TestDoubles.dimension(EVALUATION_ID, "content", "82.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "expression", "72.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "interaction", "80.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "board", "81.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "time", "90.0"));
    }

    private EvaluationResultCommand result(
            int attempt, List<DimensionScore> scores, List<EvaluationIssue> issueList,
            List<String> warnings, List<String> partialReasons) {
        return new EvaluationResultCommand(
                EVALUATION_ID,
                JOB_ID,
                attempt,
                "completed",
                "mock-model",
                "m4-eval-v1",
                "m4-rubric-v1",
                scores,
                issueList,
                warnings,
                partialReasons,
                Map.of(),
                Map.of("total_tokens", 100));
    }

    @Test
    void startMarksEvaluationAndJobRunning() {
        var result = service.start(new StartEvaluationCommand(EVALUATION_ID, JOB_ID, 1));

        assertThat(result.ignored()).isFalse();
        assertThat(result.status()).isEqualTo("running");
        assertThat(evaluations.findById(EVALUATION_ID).orElseThrow().status())
                .isEqualTo(EvaluationStatus.RUNNING);
        assertThat(jobs.findById(JOB_ID).orElseThrow().status())
                .isEqualTo(EvaluationJobStatus.RUNNING);
    }

    @Test
    void startWithNewerAttemptIsRejected() {
        assertThatThrownBy(() ->
                service.start(new StartEvaluationCommand(EVALUATION_ID, JOB_ID, 2)))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_ATTEMPT_STALE));
    }

    @Test
    void replayOfAnOlderAttemptIsIgnored() {
        jobs.save(EvaluationJob.rehydrate(
                JOB_ID,
                EVALUATION_ID,
                EvaluationJobStatus.RUNNING,
                2,
                null,
                null,
                NOW,
                NOW));

        var result = service.start(new StartEvaluationCommand(EVALUATION_ID, JOB_ID, 1));

        assertThat(result.ignored()).isTrue();
        assertThat(result.status()).isEqualTo("pending");
    }

    @Test
    void jobFromAnotherEvaluationIsRejected() {
        UUID otherJob = UUID.fromString("44444444-dddd-dddd-dddd-444444444444");
        jobs.save(M4TestDoubles.queuedJob(
                otherJob, UUID.fromString("55555555-eeee-eeee-eeee-555555555555")));

        assertThatThrownBy(() ->
                service.start(new StartEvaluationCommand(EVALUATION_ID, otherJob, 1)))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_NOT_FOUND));
    }

    @Test
    void completedResultComputesWeightedTotalRoundsAndPublishesTerminal() {
        List<DimensionScore> scores = allDimensions().stream()
                .map(score -> new DimensionScore(
                        score.id(),
                        score.evaluationId(),
                        score.dimension(),
                        score.dimension().equals("content")
                                ? new BigDecimal("82.44") : score.score(),
                        score.subScores(),
                        score.subConfidences(),
                        score.confidence()))
                .toList();

        var result = service.complete(result(1, scores, List.of(), List.of(), List.of()));

        Evaluation evaluation = evaluations.findById(EVALUATION_ID).orElseThrow();
        assertThat(result.ignored()).isFalse();
        assertThat(result.status()).isEqualTo("completed");
        assertThat(evaluation.status()).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(evaluation.totalScore()).isNotNull();
        assertThat(evaluation.totalScore().scale()).isEqualTo(1);
        assertThat(evaluation.totalScore()).isEqualTo(new BigDecimal("80.8"));
        assertThat(evaluation.completedAt()).isEqualTo(NOW);
        assertThat(evaluation.modelVersion()).isEqualTo("mock-model");
        assertThat(evaluation.promptVersion()).isEqualTo("m4-eval-v1");
        assertThat(evaluation.tokenUsage()).containsEntry("total_tokens", 100);
        assertThat(dimensions.findByEvaluationId(EVALUATION_ID)).hasSize(5);
        assertThat(jobs.findById(JOB_ID).orElseThrow().status())
                .isEqualTo(EvaluationJobStatus.COMPLETED);

        assertThat(events.terminals()).hasSize(1);
        EvaluationTerminalEvent terminal = events.terminals().get(0);
        assertThat(terminal.evaluationId()).isEqualTo(EVALUATION_ID);
        assertThat(terminal.status()).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(terminal.attempt()).isEqualTo(1);
    }

    @Test
    void missingRequiredDimensionProducesPartialWithoutTotal() {
        List<DimensionScore> scores = List.of(
                M4TestDoubles.dimension(EVALUATION_ID, "content"),
                M4TestDoubles.dimension(EVALUATION_ID, "expression"),
                M4TestDoubles.dimension(EVALUATION_ID, "interaction"),
                M4TestDoubles.dimension(EVALUATION_ID, "time"));

        service.complete(result(1, scores, List.of(), List.of(), List.of()));

        Evaluation evaluation = evaluations.findById(EVALUATION_ID).orElseThrow();
        assertThat(evaluation.status()).isEqualTo(EvaluationStatus.PARTIAL);
        assertThat(evaluation.totalScore()).isNull();
        assertThat(evaluation.partialReasons()).contains("DIMENSION_FAILED");
        assertThat(evaluation.completedAt()).isEqualTo(NOW);
        assertThat(jobs.findById(JOB_ID).orElseThrow().status())
                .isEqualTo(EvaluationJobStatus.PARTIAL);
        assertThat(events.terminals()).hasSize(1);
        assertThat(events.terminals().get(0).status()).isEqualTo(EvaluationStatus.PARTIAL);
    }

    @Test
    void emptyResultFailsTheEvaluation() {
        service.complete(result(1, List.of(), List.of(), List.of(), List.of()));

        Evaluation evaluation = evaluations.findById(EVALUATION_ID).orElseThrow();
        assertThat(evaluation.status()).isEqualTo(EvaluationStatus.FAILED);
        assertThat(evaluation.totalScore()).isNull();
        assertThat(evaluation.errorCode())
                .isEqualTo(EvaluationErrorCode.EVALUATION_INVALID_RESULT.name());
        assertThat(dimensions.findByEvaluationId(EVALUATION_ID)).isEmpty();
        assertThat(jobs.findById(JOB_ID).orElseThrow().status())
                .isEqualTo(EvaluationJobStatus.FAILED);
        assertThat(events.terminals()).hasSize(1);
    }

    @Test
    void invalidDimensionPayloadIsRejectedWithoutWritingAnything() {
        List<DimensionScore> scores = List.of(
                M4TestDoubles.dimension(EVALUATION_ID, "content", "101.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "expression"),
                M4TestDoubles.dimension(EVALUATION_ID, "interaction"),
                M4TestDoubles.dimension(EVALUATION_ID, "board"),
                M4TestDoubles.dimension(EVALUATION_ID, "time"));

        assertThatThrownBy(() ->
                service.complete(result(1, scores, List.of(), List.of(), List.of())))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_INVALID_RESULT));

        assertThat(evaluations.findById(EVALUATION_ID).orElseThrow().status())
                .isEqualTo(EvaluationStatus.PENDING);
        assertThat(dimensions.findByEvaluationId(EVALUATION_ID)).isEmpty();
        assertThat(events.events).isEmpty();
    }

    @Test
    void duplicateIssuesAreNormalizedAndSuggestionsKeepAnchors() {
        EvaluationIssue second = M4TestDoubles.suggestion(EVALUATION_ID, "board", 2, 5000);
        EvaluationIssue first = M4TestDoubles.suggestion(EVALUATION_ID, "content", 1, 1000);

        service.complete(result(
                1, allDimensions(), List.of(second, first), List.of(), List.of()));

        List<EvaluationIssue> stored = issues.findByEvaluationId(EVALUATION_ID);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).dimension()).isEqualTo("content");
        assertThat(stored.get(0).sortOrder()).isZero();
        assertThat(stored.get(1).dimension()).isEqualTo("board");
        assertThat(stored.get(1).sortOrder()).isEqualTo(1);
        assertThat(stored).allMatch(EvaluationIssue::isSuggestion);
        assertThat(stored).allMatch(issue -> issue.startMs() != null);
    }

    @Test
    void terminalEvaluationRejectsFurtherCallbacks() {
        service.complete(result(1, allDimensions(), List.of(), List.of(), List.of()));

        var replay = service.complete(result(1, allDimensions(), List.of(), List.of(), List.of()));
        var failure = service.fail(new EvaluationFailCommand(
                EVALUATION_ID, JOB_ID, 1, "AI_TIMEOUT", "timeout", List.of()));

        assertThat(replay.ignored()).isTrue();
        assertThat(failure.ignored()).isTrue();
        assertThat(events.terminals()).hasSize(1);
        assertThat(evaluations.findById(EVALUATION_ID).orElseThrow().status())
                .isEqualTo(EvaluationStatus.COMPLETED);
    }

    @Test
    void failWithoutPartialScoresMarksEvaluationFailed() {
        var result = service.fail(new EvaluationFailCommand(
                EVALUATION_ID,
                JOB_ID,
                1,
                "AI_TIMEOUT",
                "model timed out",
                List.of("DIMENSION_TIMEOUT")));

        Evaluation evaluation = evaluations.findById(EVALUATION_ID).orElseThrow();
        assertThat(result.ignored()).isFalse();
        assertThat(evaluation.status()).isEqualTo(EvaluationStatus.FAILED);
        assertThat(evaluation.errorCode()).isEqualTo("AI_TIMEOUT");
        assertThat(evaluation.errorMessage()).isEqualTo("model timed out");
        assertThat(evaluation.partialReasons()).containsExactly("DIMENSION_TIMEOUT");
        assertThat(jobs.findById(JOB_ID).orElseThrow().status())
                .isEqualTo(EvaluationJobStatus.FAILED);
        assertThat(events.terminals()).hasSize(1);
    }

    @Test
    void failWithExistingDimensionsKeepsPartialResult() {
        dimensions.replaceAll(EVALUATION_ID, List.of(
                M4TestDoubles.dimension(EVALUATION_ID, "content")));

        service.fail(new EvaluationFailCommand(
                EVALUATION_ID, JOB_ID, 1, "AI_TIMEOUT", "timeout", List.of()));

        Evaluation evaluation = evaluations.findById(EVALUATION_ID).orElseThrow();
        assertThat(evaluation.status()).isEqualTo(EvaluationStatus.PARTIAL);
        assertThat(evaluation.totalScore()).isNull();
        assertThat(evaluation.partialReasons()).containsExactly("DIMENSION_TIMEOUT");
        assertThat(jobs.findById(JOB_ID).orElseThrow().status())
                .isEqualTo(EvaluationJobStatus.PARTIAL);
        assertThat(events.terminals()).hasSize(1);
    }

    @Test
    void failDefaultsToServiceUnavailableWhenWorkerOmitsTheCode() {
        service.fail(new EvaluationFailCommand(
                EVALUATION_ID, JOB_ID, 1, null, "worker crashed", List.of()));

        assertThat(evaluations.findById(EVALUATION_ID).orElseThrow().errorCode())
                .isEqualTo(EvaluationErrorCode.AI_SERVICE_UNAVAILABLE.name());
    }
}
