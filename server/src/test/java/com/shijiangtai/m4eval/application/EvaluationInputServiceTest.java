package com.shijiangtai.m4eval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m4eval.application.M4TestDoubles.FakeObjectStorage;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryEvaluations;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemorySnapshots;
import com.shijiangtai.m4eval.application.M4TestDoubles.StubInputQuery;
import com.shijiangtai.m4eval.application.port.EvaluationInputQuery;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import com.shijiangtai.m4eval.domain.InputSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Manifest assembly and snapshot-bounded paging (M4-T02). */
class EvaluationInputServiceTest {

    private static final UUID EVALUATION_ID =
            UUID.fromString("11111111-aaaa-aaaa-aaaa-111111111111");
    private static final UUID SUBMISSION_ID =
            UUID.fromString("33333333-cccc-cccc-cccc-333333333333");
    private static final UUID RECORDING_1 =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID RECORDING_2 =
            UUID.fromString("cccccccc-dddd-dddd-dddd-cccccccccccc");
    private static final UUID TEXT_ID =
            UUID.fromString("dddddddd-eeee-eeee-eeee-dddddddddddd");

    private InMemoryEvaluations evaluations;
    private InMemorySnapshots snapshots;
    private StubInputQuery inputs;
    private FakeObjectStorage storage;
    private EvaluationInputService service;

    @BeforeEach
    void setUp() {
        evaluations = new InMemoryEvaluations();
        snapshots = new InMemorySnapshots();
        inputs = new StubInputQuery();
        storage = new FakeObjectStorage();
        service = new EvaluationInputService(evaluations, snapshots, inputs, storage);
        evaluations.save(M4TestDoubles.pendingSubmission(EVALUATION_ID, SUBMISSION_ID));
        inputs.submission = M4TestDoubles.submission(SUBMISSION_ID);
        inputs.session = M4TestDoubles.session();
        snapshots.save(new InputSnapshot(
                EVALUATION_ID,
                Evaluation.SOURCE_TASK_SUBMISSION,
                SUBMISSION_ID,
                SUBMISSION_ID,
                null,
                List.of(RECORDING_1),
                List.of(TEXT_ID),
                List.of(),
                List.of(),
                4,
                4,
                null,
                Map.of(InputFlags.TRANSCRIPT_COMPLETE, true),
                Instant.parse("2026-11-23T08:00:00Z")));
    }

    private static EvaluationInputQuery.RecordingStreamRow stream(
            UUID id, String type, String status) {
        return new EvaluationInputQuery.RecordingStreamRow(
                id, type, "sessions/" + type + ".webm", 900_000L, 0L,
                "video/webm", status);
    }

    @Test
    void manifestOnlyExposesRecordingsCapturedInTheSnapshot() {
        inputs.recordings = List.of(
                stream(RECORDING_1, "camera", "uploaded"),
                stream(RECORDING_2, "screen", "uploaded"));

        var manifest = service.manifest(EVALUATION_ID);

        assertThat(manifest.recordings()).hasSize(1);
        assertThat(manifest.recordings().get(0).streamId()).isEqualTo(RECORDING_1);
        assertThat(manifest.recordings().get(0).url()).contains("sessions/camera.webm");
        assertThat(storage.presigned).containsExactly("sessions/camera.webm");
        assertThat(storage.presignedTtls).containsExactly(java.time.Duration.ofMinutes(15));
    }

    @Test
    void lateUploadedRecordingCannotChangeAnExistingManifest() {
        snapshots.save(new InputSnapshot(
                EVALUATION_ID,
                Evaluation.SOURCE_TASK_SUBMISSION,
                SUBMISSION_ID,
                SUBMISSION_ID,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                4,
                4,
                null,
                Map.of(),
                Instant.parse("2026-11-23T08:00:00Z")));
        inputs.recordings = List.of(stream(RECORDING_2, "audio", "uploaded"));

        var manifest = service.manifest(EVALUATION_ID);

        assertThat(manifest.recordings()).isEmpty();
        assertThat(storage.presigned).isEmpty();
    }

    @Test
    void incompleteRecordingsAreExcluded() {
        inputs.recordings = List.of(
                stream(RECORDING_1, "audio", "uploading"),
                new EvaluationInputQuery.RecordingStreamRow(
                        RECORDING_1, "audio", "  ", 0L, 0L, "audio/webm", "uploaded"));

        var manifest = service.manifest(EVALUATION_ID);

        assertThat(manifest.recordings()).isEmpty();
    }

    @Test
    void manifestCarriesConfigCountsWeightsRubricAndFlags() {
        inputs.textCount = 4;
        inputs.frameCount = 2;
        inputs.eventCount = 3;

        var manifest = service.manifest(EVALUATION_ID);

        assertThat(manifest.evaluationId()).isEqualTo(EVALUATION_ID);
        assertThat(manifest.mode()).isEqualTo("free");
        assertThat(manifest.weights()).containsEntry("content", 30);
        assertThat(manifest.rubricVersion()).isEqualTo("m4-rubric-v1");
        assertThat(manifest.counts().texts()).isEqualTo(4);
        assertThat(manifest.counts().frames()).isEqualTo(2);
        assertThat(manifest.counts().events()).isEqualTo(3);
        assertThat(manifest.session().durationMs()).isEqualTo(900_000L);
        assertThat(manifest.config())
                .containsEntry("topic", "分数的初步认识")
                .containsKey("reference_material")
                .containsKey("answer_points");
        assertThat(manifest.flags()).containsEntry(InputFlags.TRANSCRIPT_COMPLETE, true);
    }

    @Test
    void pagingClampsTheRequestedLimit() {
        inputs.textRows = List.of(
                new EvaluationInputQuery.TextRow(
                        TEXT_ID, "teacher", null, "asr", "utterance", "文本",
                        0L, 1000L, 0L, 0.9, "asr-v1", null),
                new EvaluationInputQuery.TextRow(
                        UUID.randomUUID(), "teacher", null, "asr", "utterance", "文本2",
                        1000L, 2000L, 1000L, 0.9, "asr-v1", null));

        var page = service.texts(EVALUATION_ID, null, 99_999);

        assertThat(page.size()).isEqualTo(500);
        assertThat(page.items()).hasSize(2);
    }

    @Test
    void missingSnapshotIsNotFound() {
        snapshots.byEvaluation.clear();

        assertThatThrownBy(() -> service.manifest(EVALUATION_ID))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_NOT_FOUND));
    }

    @Test
    void missingEvaluationIsNotFound() {
        evaluations.byId.clear();

        assertThatThrownBy(() -> service.texts(EVALUATION_ID, null, 10))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_NOT_FOUND));
    }
}
