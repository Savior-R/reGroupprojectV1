package com.shijiangtai.m4eval.application;

import static com.shijiangtai.m4eval.application.M4TestDoubles.COURSE_ID;
import static com.shijiangtai.m4eval.application.M4TestDoubles.NOW;
import static com.shijiangtai.m4eval.application.M4TestDoubles.SESSION_ID;
import static com.shijiangtai.m4eval.application.M4TestDoubles.STUDENT_ID;
import static com.shijiangtai.m4eval.application.M4TestDoubles.submission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m4eval.application.M4TestDoubles.ImmediateAfterCommit;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryDimensions;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryEvaluations;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryIssues;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryJobs;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemorySnapshots;
import com.shijiangtai.m4eval.application.M4TestDoubles.MutableClock;
import com.shijiangtai.m4eval.application.M4TestDoubles.RecordingQueue;
import com.shijiangtai.m4eval.application.M4TestDoubles.SequentialIds;
import com.shijiangtai.m4eval.application.M4TestDoubles.StubInputQuery;
import com.shijiangtai.m4eval.application.port.EvaluationInputQuery;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import com.shijiangtai.m4eval.domain.EvaluationJob;
import com.shijiangtai.m4eval.domain.EvaluationJobStatus;
import com.shijiangtai.m4eval.domain.EvaluationMode;
import com.shijiangtai.m4eval.domain.EvaluationStatus;
import com.shijiangtai.m4eval.domain.InputSnapshot;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Trigger, idempotency and input snapshot assembly (M4-T01 / T02 / T11). */
class EvaluationApplicationServiceTest {

    private static final UUID SUBMISSION_ID =
            UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID TRAINING_RECORD_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private InMemoryEvaluations evaluations;
    private InMemoryJobs jobs;
    private InMemorySnapshots snapshots;
    private StubInputQuery inputs;
    private RecordingQueue queue;
    private ImmediateAfterCommit afterCommit;
    private EvaluationApplicationService service;

    @BeforeEach
    void setUp() {
        evaluations = new InMemoryEvaluations();
        jobs = new InMemoryJobs();
        snapshots = new InMemorySnapshots();
        inputs = new StubInputQuery();
        queue = new RecordingQueue();
        afterCommit = new ImmediateAfterCommit();
        service = new EvaluationApplicationService(
                evaluations,
                jobs,
                snapshots,
                inputs,
                queue,
                afterCommit,
                new EvaluationAccessPolicy(inputs),
                new SequentialIds(100),
                new MutableClock(NOW));
    }

    @Test
    void submissionTriggerCreatesPendingEvaluationJobSnapshotAndDispatch() {
        inputs.submission = submission(SUBMISSION_ID);
        inputs.session = M4TestDoubles.session();
        inputs.textCount = 4;
        inputs.frameCount = 2;
        inputs.eventCount = 3;
        UUID recordingId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        inputs.recordingIds = List.of(recordingId);
        inputs.recordings = List.of(new EvaluationInputQuery.RecordingStreamRow(
                recordingId,
                "audio",
                "sessions/audio.webm",
                900_000L,
                0L,
                "audio/webm",
                "uploaded"));
        inputs.textIds = List.of(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
        inputs.frameIds = List.of(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
        inputs.eventIds = List.of(UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"));

        Evaluation created = service.getOrCreateForSubmission(SUBMISSION_ID);

        assertThat(created.status()).isEqualTo(EvaluationStatus.PENDING);
        assertThat(created.sourceType()).isEqualTo(Evaluation.SOURCE_TASK_SUBMISSION);
        assertThat(created.sourceId()).isEqualTo(SUBMISSION_ID);
        assertThat(created.submissionId()).isEqualTo(SUBMISSION_ID);
        assertThat(created.sessionId()).isEqualTo(SESSION_ID);
        assertThat(created.courseId()).isEqualTo(COURSE_ID);
        assertThat(created.studentAccountId()).isEqualTo(STUDENT_ID);
        assertThat(created.mode()).isEqualTo(EvaluationMode.FREE);
        assertThat(created.weights()).containsEntry("content", 30);
        assertThat(created.taskConfigVersion()).isEqualTo(4);

        EvaluationJob job = jobs.only();
        assertThat(job.evaluationId()).isEqualTo(created.id());
        assertThat(job.status()).isEqualTo(EvaluationJobStatus.QUEUED);
        assertThat(job.attempts()).isEqualTo(1);

        InputSnapshot snapshot = snapshots.findByEvaluationId(created.id()).orElseThrow();
        assertThat(snapshot.recordingIds()).hasSize(1);
        assertThat(snapshot.textIds()).hasSize(1);
        assertThat(snapshot.frameIds()).hasSize(1);
        assertThat(snapshot.eventIds()).hasSize(1);
        assertThat(snapshot.configVersion()).isEqualTo(4);
        assertThat(snapshot.flags())
                .containsEntry(InputFlags.TRANSCRIPT_COMPLETE, true)
                .containsEntry(InputFlags.AUDIO_COMPLETE, true)
                .containsEntry(InputFlags.FRAMES_COMPLETE, true)
                .containsEntry(InputFlags.EVENTS_COMPLETE, true)
                .containsEntry(InputFlags.REFERENCE_COMPLETE, true)
                .containsEntry(InputFlags.ANSWER_POINTS_COMPLETE, true);

        assertThat(queue.published).hasSize(1);
        assertThat(queue.published.get(0).jobId()).isEqualTo(job.id());
        assertThat(queue.published.get(0).evaluationId()).isEqualTo(created.id());
        assertThat(queue.published.get(0).attempt()).isEqualTo(1);
        assertThat(queue.published.get(0).publishedAt()).isEqualTo(NOW);
        assertThat(afterCommit.count()).isEqualTo(1);

        assertThat(created.attemptCount()).isZero();
    }

    @Test
    void duplicateSubmissionTriggerReturnsExistingEvaluationWithoutNewJob() {
        inputs.submission = submission(SUBMISSION_ID);
        inputs.session = M4TestDoubles.session();
        inputs.textCount = 1;

        Evaluation first = service.getOrCreateForSubmission(SUBMISSION_ID);
        Evaluation second = service.getOrCreateForSubmission(SUBMISSION_ID);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jobs.byId).hasSize(1);
        assertThat(queue.published).hasSize(1);
        assertThat(snapshots.byEvaluation).hasSize(1);
    }

    @Test
    void missingInputsAreRecordedInSnapshotFlags() {
        inputs.submission = submission(SUBMISSION_ID);
        inputs.session = M4TestDoubles.session();
        inputs.textCount = 0;
        inputs.frameCount = 0;
        inputs.eventCount = 0;
        inputs.recordingIds = List.of();

        Evaluation created = service.getOrCreateForSubmission(SUBMISSION_ID);

        InputSnapshot snapshot = snapshots.findByEvaluationId(created.id()).orElseThrow();
        assertThat(snapshot.flags())
                .containsEntry(InputFlags.TRANSCRIPT_COMPLETE, false)
                .containsEntry(InputFlags.AUDIO_COMPLETE, false)
                .containsEntry(InputFlags.FRAMES_COMPLETE, false);
        assertThat(snapshot.flags().get(InputFlags.MISSING))
                .isEqualTo(List.of("TRANSCRIPT_MISSING", "AUDIO_MISSING", "FRAMES_MISSING"));
    }

    @Test
    void trainingRecordTriggerUsesTrainingConfigVersion() {
        UUID recordId = TRAINING_RECORD_ID;
        inputs.training = M4TestDoubles.training(
                recordId, "selected", NOW.plusSeconds(86_400));
        inputs.session = M4TestDoubles.session();
        inputs.textCount = 2;
        inputs.eventCount = 1;

        Evaluation created = service.getOrCreateForTrainingRecord(recordId);

        assertThat(created.sourceType()).isEqualTo(Evaluation.SOURCE_TRAINING_RECORD);
        assertThat(created.trainingRecordId()).isEqualTo(recordId);
        assertThat(created.submissionId()).isNull();
        assertThat(created.trainingConfigVersion()).isEqualTo(2);

        InputSnapshot snapshot = snapshots.findByEvaluationId(created.id()).orElseThrow();
        assertThat(snapshot.configVersion()).isEqualTo(2);
        assertThat(snapshot.flags()).containsEntry(InputFlags.REFERENCE_COMPLETE, true);
    }

    @Test
    void missingSubmissionIsNotFound() {
        assertThatThrownBy(() -> service.getOrCreateForSubmission(SUBMISSION_ID))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_NOT_FOUND));
    }

    @Test
    void missingSessionIsNotFound() {
        inputs.submission = submission(SUBMISSION_ID);
        inputs.sessionLookupFails = true;

        assertThatThrownBy(() -> service.getOrCreateForSubmission(SUBMISSION_ID))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_NOT_FOUND));
    }

    @Test
    void dispatchFailureIsSwallowedSoTheJobStaysQueuedForTheScheduler() {
        inputs.submission = submission(SUBMISSION_ID);
        inputs.session = M4TestDoubles.session();
        queue.failPublish = true;

        Evaluation created = service.getOrCreateForSubmission(SUBMISSION_ID);

        assertThat(created.status()).isEqualTo(EvaluationStatus.PENDING);
        assertThat(jobs.only().status()).isEqualTo(EvaluationJobStatus.QUEUED);
        assertThat(queue.published).isEmpty();
    }

    @Test
    void statusReadEnforcesAccessPolicy() {
        inputs.submission = submission(SUBMISSION_ID);
        inputs.session = M4TestDoubles.session();
        Evaluation created = service.getOrCreateForSubmission(SUBMISSION_ID);

        var owner = new com.shijiangtai.shared.application.CurrentUser(
                new com.shijiangtai.shared.domain.AccountId(STUDENT_ID),
                new com.shijiangtai.shared.domain.SchoolId(M4TestDoubles.SCHOOL_ID),
                com.shijiangtai.shared.application.AccountType.STUDENT);

        assertThat(service.get(owner, created.id()).evaluationId()).isEqualTo(created.id());
        assertThat(service.getBySubmission(owner, SUBMISSION_ID).status()).isEqualTo("pending");
    }

    @Test
    void trainingRecordReconcilerTargetsAreReturnedInOrder() {
        evaluations.trainingRecordsWithoutEvaluation.add(TRAINING_RECORD_ID);
        inputs.training = M4TestDoubles.training(
                TRAINING_RECORD_ID, "selected", NOW.plusSeconds(86_400));
        inputs.session = M4TestDoubles.session();

        new EvaluationReconciler(evaluations, service).reconcile();

        assertThat(evaluations.byId).hasSize(1);
        assertThat(queue.published).hasSize(1);
        assertThat(evaluations.byId.values().iterator().next().sourceId())
                .isEqualTo(TRAINING_RECORD_ID);
    }

    @Test
    void snapshotKeepsLateIdsOutOfAnExistingEvaluation() {
        inputs.submission = submission(SUBMISSION_ID);
        inputs.session = M4TestDoubles.session();
        inputs.textCount = 1;
        inputs.textIds = List.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        Evaluation created = service.getOrCreateForSubmission(SUBMISSION_ID);

        inputs.textIds = List.of(UUID.fromString("22222222-3333-4444-5555-666666666666"));
        Evaluation again = service.getOrCreateForSubmission(SUBMISSION_ID);

        assertThat(again.id()).isEqualTo(created.id());
        assertThat(snapshots.findByEvaluationId(created.id()).orElseThrow().textIds())
                .containsExactly(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void evaluationHasNoTotalUntilAWorkerReportsOne() {
        inputs.submission = submission(SUBMISSION_ID);
        inputs.session = M4TestDoubles.session();

        Evaluation created = service.getOrCreateForSubmission(SUBMISSION_ID);

        assertThat(created.totalScore()).isNull();
        assertThat(created.partialReasons()).isEmpty();
        assertThat(created.errorCode()).isNull();
    }
}
