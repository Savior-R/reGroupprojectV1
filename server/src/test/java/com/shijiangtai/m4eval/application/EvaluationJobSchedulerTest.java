package com.shijiangtai.m4eval.application;

import static com.shijiangtai.m4eval.application.M4TestDoubles.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m4eval.application.M4TestDoubles.ImmediateAfterCommit;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryDimensions;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryEvaluations;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryJobs;
import com.shijiangtai.m4eval.application.M4TestDoubles.MutableClock;
import com.shijiangtai.m4eval.application.M4TestDoubles.RecordingEvents;
import com.shijiangtai.m4eval.application.M4TestDoubles.RecordingQueue;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationJob;
import com.shijiangtai.m4eval.domain.EvaluationJobStatus;
import com.shijiangtai.m4eval.domain.EvaluationOutcome;
import com.shijiangtai.m4eval.domain.EvaluationStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Retry / backoff / reclaim / dead-letter loop (M4-T06). */
class EvaluationJobSchedulerTest {

    private static final UUID EVALUATION_ID =
            UUID.fromString("11111111-aaaa-aaaa-aaaa-111111111111");
    private static final UUID JOB_ID =
            UUID.fromString("22222222-bbbb-bbbb-bbbb-222222222222");
    private static final UUID SUBMISSION_ID =
            UUID.fromString("33333333-cccc-cccc-cccc-333333333333");

    private InMemoryEvaluations evaluations;
    private InMemoryJobs jobs;
    private InMemoryDimensions dimensions;
    private RecordingQueue queue;
    private RecordingEvents events;
    private MutableClock clock;
    private EvaluationJobScheduler scheduler;

    @BeforeEach
    void setUp() {
        evaluations = new InMemoryEvaluations();
        jobs = new InMemoryJobs();
        dimensions = new InMemoryDimensions();
        queue = new RecordingQueue();
        events = new RecordingEvents();
        clock = new MutableClock(NOW);
        scheduler = new EvaluationJobScheduler(
                evaluations,
                jobs,
                dimensions,
                queue,
                new ImmediateAfterCommit(),
                events,
                clock);
        evaluations.save(M4TestDoubles.pendingSubmission(EVALUATION_ID, SUBMISSION_ID));
    }

    private EvaluationJob queued(int attempts) {
        EvaluationJob job = EvaluationJob.rehydrate(
                JOB_ID,
                EVALUATION_ID,
                EvaluationJobStatus.QUEUED,
                attempts,
                null,
                NOW.minusSeconds(1),
                NOW.minusSeconds(120),
                NOW.minusSeconds(120));
        jobs.save(job);
        return job;
    }

    @Test
    void backoffIsFifteenSixtyOneEightyAndCapsAtThreeMinutes() {
        assertThat(EvaluationJobScheduler.backoffSeconds(1)).isEqualTo(15);
        assertThat(EvaluationJobScheduler.backoffSeconds(2)).isEqualTo(60);
        assertThat(EvaluationJobScheduler.backoffSeconds(3)).isEqualTo(180);
        assertThat(EvaluationJobScheduler.backoffSeconds(4)).isEqualTo(180);
    }

    @Test
    void dispatchRequeuesQueuedJobAsNextAttempt() {
        queued(1);

        scheduler.dispatchQueued();

        EvaluationJob job = jobs.findById(JOB_ID).orElseThrow();
        assertThat(job.status()).isEqualTo(EvaluationJobStatus.QUEUED);
        assertThat(job.attempts()).isEqualTo(2);
        assertThat(job.nextRetryAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(queue.published).hasSize(1);
        assertThat(queue.published.get(0).attempt()).isEqualTo(2);
        assertThat(queue.published.get(0).jobId()).isEqualTo(JOB_ID);
    }

    @Test
    void dispatchSkipsJobsWhoseBackoffHasNotElapsed() {
        jobs.save(EvaluationJob.rehydrate(
                JOB_ID,
                EVALUATION_ID,
                EvaluationJobStatus.QUEUED,
                1,
                null,
                NOW.plusSeconds(30),
                NOW,
                NOW));

        scheduler.dispatchQueued();

        assertThat(queue.published).isEmpty();
        assertThat(jobs.findById(JOB_ID).orElseThrow().attempts()).isEqualTo(1);
    }

    @Test
    void dispatchMarksJobTerminalWhenEvaluationIsAlreadyTerminal() {
        evaluations.save(terminalEvaluation(EvaluationStatus.COMPLETED));
        queued(1);

        scheduler.dispatchQueued();

        assertThat(jobs.findById(JOB_ID).orElseThrow().status())
                .isEqualTo(EvaluationJobStatus.COMPLETED);
        assertThat(queue.published).isEmpty();
    }

    @Test
    void dispatchFailsJobWhenEvaluationRowDisappeared() {
        evaluations.byId.clear();
        queued(1);

        scheduler.dispatchQueued();

        EvaluationJob job = jobs.findById(JOB_ID).orElseThrow();
        assertThat(job.status()).isEqualTo(EvaluationJobStatus.FAILED);
        assertThat(job.lastError()).isEqualTo("evaluation row missing");
        assertThat(queue.published).isEmpty();
    }

    @Test
    void exhaustedRetriesDeadLetterFailedEvaluation() {
        queued(3);

        scheduler.dispatchQueued();

        EvaluationJob job = jobs.findById(JOB_ID).orElseThrow();
        assertThat(job.status()).isEqualTo(EvaluationJobStatus.FAILED);
        assertThat(job.lastError()).isEqualTo("retry exhausted");
        Evaluation evaluation = evaluations.findById(EVALUATION_ID).orElseThrow();
        assertThat(evaluation.status()).isEqualTo(EvaluationStatus.FAILED);
        assertThat(evaluation.errorCode())
                .isEqualTo(EvaluationErrorCode.EVALUATION_TIMEOUT.name());
        assertThat(queue.deadLettered).hasSize(1);
        assertThat(queue.deadLetterReason).isEqualTo("retry exhausted");
        assertThat(events.terminals()).hasSize(1);
        assertThat(events.terminals().get(0).status()).isEqualTo(EvaluationStatus.FAILED);
    }

    @Test
    void exhaustedRetriesKeepFinishedDimensionsAsPartial() {
        dimensions.replaceAll(EVALUATION_ID, List.of(
                M4TestDoubles.dimension(EVALUATION_ID, "content")));
        queued(3);

        scheduler.dispatchQueued();

        Evaluation evaluation = evaluations.findById(EVALUATION_ID).orElseThrow();
        assertThat(evaluation.status()).isEqualTo(EvaluationStatus.PARTIAL);
        assertThat(evaluation.totalScore()).isNull();
        assertThat(evaluation.partialReasons()).containsExactly("DIMENSION_TIMEOUT");
        assertThat(jobs.findById(JOB_ID).orElseThrow().status())
                .isEqualTo(EvaluationJobStatus.PARTIAL);
        assertThat(queue.deadLettered).hasSize(1);
        assertThat(events.terminals()).hasSize(1);
        assertThat(events.terminals().get(0).status()).isEqualTo(EvaluationStatus.PARTIAL);
    }

    @Test
    void staleRunningJobIsReclaimedWithTheNextAttempt() {
        clock.advance(Duration.ofMinutes(11));
        jobs.save(EvaluationJob.rehydrate(
                JOB_ID,
                EVALUATION_ID,
                EvaluationJobStatus.RUNNING,
                1,
                null,
                null,
                NOW,
                NOW));

        scheduler.requeueStale();

        EvaluationJob job = jobs.findById(JOB_ID).orElseThrow();
        assertThat(job.status()).isEqualTo(EvaluationJobStatus.QUEUED);
        assertThat(job.attempts()).isEqualTo(2);
        assertThat(queue.published).hasSize(1);
        assertThat(queue.published.get(0).attempt()).isEqualTo(2);
    }

    @Test
    void recentRunningJobIsNotReclaimed() {
        jobs.save(EvaluationJob.rehydrate(
                JOB_ID,
                EVALUATION_ID,
                EvaluationJobStatus.RUNNING,
                1,
                null,
                null,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60)));

        scheduler.requeueStale();

        assertThat(jobs.findById(JOB_ID).orElseThrow().status())
                .isEqualTo(EvaluationJobStatus.RUNNING);
        assertThat(queue.published).isEmpty();
    }

    @Test
    void staleJobOnTerminalEvaluationIsClosedInsteadOfRequeued() {
        evaluations.save(terminalEvaluation(EvaluationStatus.COMPLETED));
        clock.advance(Duration.ofMinutes(11));
        jobs.save(EvaluationJob.rehydrate(
                JOB_ID,
                EVALUATION_ID,
                EvaluationJobStatus.RUNNING,
                1,
                null,
                null,
                NOW,
                NOW));

        scheduler.requeueStale();

        assertThat(jobs.findById(JOB_ID).orElseThrow().status())
                .isEqualTo(EvaluationJobStatus.COMPLETED);
        assertThat(queue.published).isEmpty();
    }

    @Test
    void staleExhaustedJobIsDeadLettered() {
        clock.advance(Duration.ofMinutes(11));
        jobs.save(EvaluationJob.rehydrate(
                JOB_ID,
                EVALUATION_ID,
                EvaluationJobStatus.RUNNING,
                3,
                null,
                null,
                NOW,
                NOW));

        scheduler.requeueStale();

        assertThat(jobs.findById(JOB_ID).orElseThrow().status())
                .isEqualTo(EvaluationJobStatus.FAILED);
        assertThat(queue.deadLettered).hasSize(1);
        assertThat(events.terminals()).hasSize(1);
    }

    @Test
    void explicitDeadLetterIsIdempotentForTerminalJobs() {
        jobs.save(EvaluationJob.rehydrate(
                JOB_ID,
                EVALUATION_ID,
                EvaluationJobStatus.FAILED,
                3,
                "already failed",
                null,
                NOW,
                NOW));

        scheduler.deadLetter(JOB_ID, "manual");

        assertThat(queue.deadLettered).isEmpty();
        assertThat(events.events).isEmpty();
    }

    @Test
    void publishFailureDoesNotLoseTheQueuedJob() {
        queue.failPublish = true;
        queued(1);

        scheduler.dispatchQueued();

        EvaluationJob job = jobs.findById(JOB_ID).orElseThrow();
        assertThat(job.status()).isEqualTo(EvaluationJobStatus.QUEUED);
        assertThat(job.attempts()).isEqualTo(2);
        assertThat(job.nextRetryAt()).isEqualTo(NOW.plusSeconds(60));
    }

    private Evaluation terminalEvaluation(EvaluationStatus status) {
        Evaluation evaluation = M4TestDoubles.pendingSubmission(EVALUATION_ID, SUBMISSION_ID);
        evaluation.apply(new EvaluationOutcome(
                EvaluationStatus.COMPLETED,
                new BigDecimal("80.0"),
                List.of(),
                List.of(),
                "mock-model",
                "m4-eval-v1",
                "m4-rubric-v1",
                Map.of(),
                NOW.minusSeconds(60)));
        assertThat(evaluation.status()).isEqualTo(status);
        return evaluation;
    }
}
