package com.shijiangtai.m3classroom.domain.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingTest {
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final UUID RECORDING_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final SessionId SESSION_ID =
            SessionId.of(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

    @Test
    void recordingStatusBecomesUploadedWhenAllRequiredStreamsUploaded() {
        Recording recording = recording();
        assertThat(recording.status()).isEqualTo(RecordingStatus.RECORDING);
        assertThat(recording.streams()).isEmpty();

        RecordingStream camera = start(recording, TaskMode.FREE, StreamType.CAMERA);
        start(recording, TaskMode.FREE, StreamType.SCREEN);
        start(recording, TaskMode.FREE, StreamType.AUDIO);

        assertThat(recording.status()).isEqualTo(RecordingStatus.UPLOADING);
        assertThat(camera.recordingId()).isEqualTo(RECORDING_ID);
        assertThat(camera.type()).isEqualTo(StreamType.CAMERA);
        assertThat(camera.partSize()).isEqualTo(5L * 1024 * 1024);
        assertThat(camera.offsetMs()).isZero();
        assertThat(camera.status()).isEqualTo(StreamStatus.UPLOADING);
        assertThat(recording.streams()).extracting(RecordingStream::type)
                .containsExactly(StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO);

        upload(recording, TaskMode.FREE, StreamType.CAMERA);
        upload(recording, TaskMode.FREE, StreamType.SCREEN);
        assertThat(recording.status()).isEqualTo(RecordingStatus.UPLOADING);

        upload(recording, TaskMode.FREE, StreamType.AUDIO);
        assertThat(recording.status()).isEqualTo(RecordingStatus.UPLOADED);
        assertThat(recording.stream(StreamType.AUDIO).orElseThrow().status())
                .isEqualTo(StreamStatus.UPLOADED);
    }

    @Test
    void interviewRecordingBecomesUploadedWithCameraAndAudioOnly() {
        Recording recording = recording();
        start(recording, TaskMode.INTERVIEW, StreamType.CAMERA);
        start(recording, TaskMode.INTERVIEW, StreamType.AUDIO);

        upload(recording, TaskMode.INTERVIEW, StreamType.CAMERA);
        assertThat(recording.status()).isEqualTo(RecordingStatus.UPLOADING);

        upload(recording, TaskMode.INTERVIEW, StreamType.AUDIO);
        assertThat(recording.status()).isEqualTo(RecordingStatus.UPLOADED);
        assertThat(recording.streams()).extracting(RecordingStream::type)
                .containsExactly(StreamType.CAMERA, StreamType.AUDIO);
    }

    @Test
    void failedStreamMakesRecordingFailed() {
        Recording recording = recording();
        start(recording, TaskMode.FREE, StreamType.CAMERA);
        start(recording, TaskMode.FREE, StreamType.SCREEN);
        start(recording, TaskMode.FREE, StreamType.AUDIO);
        upload(recording, TaskMode.FREE, StreamType.CAMERA);

        recording.markStreamFailed(StreamType.SCREEN, NOW);

        assertThat(recording.status()).isEqualTo(RecordingStatus.FAILED);
        assertThat(recording.stream(StreamType.SCREEN).orElseThrow().status())
                .isEqualTo(StreamStatus.FAILED);
    }

    @Test
    void startingStreamNotRequiredByModeFails() {
        Recording recording = recording();

        assertThatThrownBy(() -> start(recording, TaskMode.INTERVIEW, StreamType.SCREEN))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SESSION_STATE_INVALID));
        assertThat(recording.streams()).isEmpty();
    }

    @Test
    void invalidStreamTransitionsFail() {
        Recording recording = recording();
        start(recording, TaskMode.FREE, StreamType.CAMERA);

        assertValidationError(() -> start(recording, TaskMode.FREE, StreamType.CAMERA));
        assertValidationError(() -> upload(recording, TaskMode.FREE, StreamType.SCREEN));

        upload(recording, TaskMode.FREE, StreamType.CAMERA);
        assertValidationError(() -> upload(recording, TaskMode.FREE, StreamType.CAMERA));
        assertValidationError(() -> recording.markStreamFailed(StreamType.CAMERA, NOW));
        assertValidationError(() -> recording.abortStream(StreamType.CAMERA, NOW));

        Recording completed = recording();
        start(completed, TaskMode.FREE, StreamType.CAMERA);
        start(completed, TaskMode.FREE, StreamType.SCREEN);
        start(completed, TaskMode.FREE, StreamType.AUDIO);
        upload(completed, TaskMode.FREE, StreamType.CAMERA);
        upload(completed, TaskMode.FREE, StreamType.SCREEN);
        upload(completed, TaskMode.FREE, StreamType.AUDIO);
        assertValidationError(() -> start(completed, TaskMode.FREE, StreamType.CAMERA));
    }

    @Test
    void abortMarksStreamFailedAndIsIdempotent() {
        Recording recording = recording();
        start(recording, TaskMode.FREE, StreamType.CAMERA);

        recording.abortStream(StreamType.CAMERA, NOW);

        assertThat(recording.stream(StreamType.CAMERA).orElseThrow().status())
                .isEqualTo(StreamStatus.FAILED);
        assertThat(recording.status()).isEqualTo(RecordingStatus.FAILED);

        recording.abortStream(StreamType.CAMERA, NOW);
        recording.abortStream(StreamType.SCREEN, NOW);
        assertThat(recording.status()).isEqualTo(RecordingStatus.FAILED);
    }

    @Test
    void markDeletedMarksRecordingAndEveryStreamDeleted() {
        Recording recording = recording();
        start(recording, TaskMode.FREE, StreamType.CAMERA);
        start(recording, TaskMode.FREE, StreamType.SCREEN);
        start(recording, TaskMode.FREE, StreamType.AUDIO);
        upload(recording, TaskMode.FREE, StreamType.CAMERA);

        recording.markDeleted(NOW);

        assertThat(recording.status()).isEqualTo(RecordingStatus.DELETED);
        assertThat(recording.streams()).extracting(RecordingStream::status)
                .containsOnly(StreamStatus.DELETED);
    }

    @Test
    void rehydrateKeepsCanonicalStreamOrder() {
        RecordingStream audio = stream(StreamType.AUDIO, StreamStatus.UPLOADED);
        RecordingStream camera = stream(StreamType.CAMERA, StreamStatus.UPLOADING);

        Recording recording = Recording.rehydrate(RECORDING_ID, SESSION_ID,
                RecordingStatus.UPLOADING, List.of(audio, camera), NOW, NOW.plusSeconds(3));

        assertThat(recording.id()).isEqualTo(RECORDING_ID);
        assertThat(recording.sessionId()).isEqualTo(SESSION_ID);
        assertThat(recording.status()).isEqualTo(RecordingStatus.UPLOADING);
        assertThat(recording.streams()).extracting(RecordingStream::type)
                .containsExactly(StreamType.CAMERA, StreamType.AUDIO);
        assertThat(recording.stream(StreamType.AUDIO)).containsSame(audio);
        assertThat(recording.sessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    void partSizeConstantIsFiveMegabytes() {
        assertThat(RecordingStream.DEFAULT_PART_SIZE).isEqualTo(5L * 1024 * 1024);
    }

    private static Recording recording() {
        return Recording.create(RECORDING_ID, SESSION_ID, NOW);
    }

    private static RecordingStream start(Recording recording, TaskMode mode, StreamType type) {
        return recording.startStream(UUID.randomUUID(), mode, type,
                "upload-" + type.wireValue(),
                "recordings/key/" + type.wireValue() + ".webm",
                RecordingStream.DEFAULT_PART_SIZE, 0L, mime(type), NOW);
    }

    private static void upload(Recording recording, TaskMode mode, StreamType type) {
        recording.markStreamUploaded(type, mode, 2, 1024L, 60_000L, mime(type), NOW);
    }

    private static RecordingStream stream(StreamType type, StreamStatus status) {
        return new RecordingStream(UUID.randomUUID(), RECORDING_ID, type,
                "upload-" + type.wireValue(),
                "recordings/key/" + type.wireValue() + ".webm",
                RecordingStream.DEFAULT_PART_SIZE, 2, 1024L, 60_000L, 0L, mime(type), status);
    }

    private static String mime(StreamType type) {
        return type == StreamType.AUDIO ? "audio/webm;codecs=opus" : "video/webm;codecs=vp8";
    }

    private static void assertValidationError(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
