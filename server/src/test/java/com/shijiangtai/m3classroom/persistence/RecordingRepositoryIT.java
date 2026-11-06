package com.shijiangtai.m3classroom.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStatus;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.RecordingStreamRepository;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.infrastructure.persistence.jpa.RecordingRepositoryAdapter;
import com.shijiangtai.m3classroom.infrastructure.persistence.jpa.RecordingStreamRepositoryAdapter;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class RecordingRepositoryIT extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final long PART_SIZE = 5L * 1024 * 1024;
    private static final String CAMERA_KEY =
            "recordings/school/task/task/session/camera.webm";
    private static final String SCREEN_KEY =
            "recordings/school/task/task/session/screen.webm";
    private static final String AUDIO_KEY =
            "recordings/school/task/task/session/audio.webm";

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private RecordingStreamRepository streamRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void realJpaAdaptersAreWired() {
        assertThat(recordingRepository).isInstanceOf(RecordingRepositoryAdapter.class);
        assertThat(streamRepository).isInstanceOf(RecordingStreamRepositoryAdapter.class);
    }

    @Test
    void savesRecordingWithThreeStreamsAndReloadsEveryField() {
        SessionId sessionId = seedSession();
        Recording recording = freeRecording(sessionId);

        recordingRepository.save(recording);

        Recording loaded = recordingRepository.findById(recording.id()).orElseThrow();
        assertThat(loaded.id()).isEqualTo(recording.id());
        assertThat(loaded.sessionId()).isEqualTo(sessionId);
        assertThat(loaded.status()).isEqualTo(RecordingStatus.UPLOADING);
        assertThat(loaded.createdAt()).isEqualTo(NOW);
        assertThat(loaded.updatedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(loaded.streams()).containsExactlyElementsOf(recording.streams());

        RecordingStream camera = loaded.stream(StreamType.CAMERA).orElseThrow();
        assertThat(camera.recordingId()).isEqualTo(recording.id());
        assertThat(camera.uploadId()).isEqualTo("upload-camera");
        assertThat(camera.objectKey()).isEqualTo(CAMERA_KEY);
        assertThat(camera.partSize()).isEqualTo(PART_SIZE);
        assertThat(camera.partsUploaded()).isZero();
        assertThat(camera.bytesUploaded()).isZero();
        assertThat(camera.durationMs()).isNull();
        assertThat(camera.offsetMs()).isZero();
        assertThat(camera.mime()).isEqualTo("video/webm");
        assertThat(camera.status()).isEqualTo(StreamStatus.UPLOADING);

        assertThat(recordingRepository.findBySession(sessionId).orElseThrow().id())
                .isEqualTo(recording.id());
        assertThat(streamRowCount(recording.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select duration_ms from m3_classroom.recordings where id = ?",
                Long.class, recording.id())).isNull();
    }

    @Test
    void savesRecordingWithoutStreams() {
        SessionId sessionId = seedSession();
        Recording recording = Recording.create(UUID.randomUUID(), sessionId, NOW);

        recordingRepository.save(recording);

        Recording loaded = recordingRepository.findById(recording.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(RecordingStatus.RECORDING);
        assertThat(loaded.streams()).isEmpty();
        assertThat(streamRowCount(recording.id())).isZero();
    }

    @Test
    void saveIsMergeOnlyAndNeverDeletesStreamsAbsentFromTheAggregate() {
        SessionId sessionId = seedSession();
        Recording recording = freeRecording(sessionId);
        recordingRepository.save(recording);
        assertThat(streamRowCount(recording.id())).isEqualTo(3);

        // Simulates a stale aggregate read by a parallel start: a save must merge the camera
        // stream but leave the sibling screen/audio rows untouched.
        Recording stale = Recording.rehydrate(
                recording.id(), sessionId, RecordingStatus.UPLOADING,
                List.of(recording.stream(StreamType.CAMERA).orElseThrow()),
                recording.createdAt(), recording.updatedAt());
        recordingRepository.save(stale);

        assertThat(streamRowCount(recording.id())).isEqualTo(3);
        Recording loaded = recordingRepository.findById(recording.id()).orElseThrow();
        assertThat(loaded.streams()).extracting(RecordingStream::type)
                .containsExactlyInAnyOrder(
                        StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO);
    }

    @Test
    void rejectsDuplicateStreamTypeForSameRecording() {
        SessionId sessionId = seedSession();
        Recording recording = freeRecording(sessionId);
        recordingRepository.save(recording);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO m3_classroom.recording_streams (id, recording_id, stream_type)
                VALUES (?, ?, 'camera')
                """, UUID.randomUUID(), recording.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(streamRowCount(recording.id())).isEqualTo(3);
    }

    @Test
    void updatesStreamToUploadedWithDurationAndSizeThenReloads() {
        SessionId sessionId = seedSession();
        Recording recording = freeRecording(sessionId);
        recordingRepository.save(recording);

        recording.markStreamUploaded(StreamType.CAMERA, TaskMode.FREE,
                2, 10_485_760L, 60_000L, "video/webm", NOW.plusSeconds(10));
        recordingRepository.save(recording);

        Recording halfway = recordingRepository.findById(recording.id()).orElseThrow();
        assertThat(halfway.status()).isEqualTo(RecordingStatus.UPLOADING);
        RecordingStream camera = halfway.stream(StreamType.CAMERA).orElseThrow();
        assertThat(camera.status()).isEqualTo(StreamStatus.UPLOADED);
        assertThat(camera.partsUploaded()).isEqualTo(2);
        assertThat(camera.bytesUploaded()).isEqualTo(10_485_760L);
        assertThat(camera.durationMs()).isEqualTo(60_000L);
        assertThat(camera.uploadId()).isEqualTo("upload-camera");
        assertThat(camera.objectKey()).isEqualTo(CAMERA_KEY);
        assertThat(camera.partSize()).isEqualTo(PART_SIZE);
        assertThat(streamRowCount(recording.id())).isEqualTo(3);

        recording.markStreamUploaded(StreamType.SCREEN, TaskMode.FREE,
                1, 4_194_304L, 60_000L, "video/webm", NOW.plusSeconds(11));
        recording.markStreamUploaded(StreamType.AUDIO, TaskMode.FREE,
                1, 512_000L, 60_000L, "audio/webm", NOW.plusSeconds(12));
        recordingRepository.save(recording);

        Recording uploaded = recordingRepository.findById(recording.id()).orElseThrow();
        assertThat(uploaded.status()).isEqualTo(RecordingStatus.UPLOADED);
        assertThat(uploaded.updatedAt()).isEqualTo(NOW.plusSeconds(12));
        assertThat(uploaded.stream(StreamType.AUDIO).orElseThrow().mime())
                .isEqualTo("audio/webm");
        assertThat(streamRowCount(recording.id())).isEqualTo(3);
    }

    @Test
    void roundTripsNullableUploadIdObjectKeyMimeAndDurationAsNull() {
        SessionId sessionId = seedSession();
        Recording recording = Recording.create(UUID.randomUUID(), sessionId, NOW);
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, StreamType.AUDIO,
                null, null, PART_SIZE, 1_500L, null, NOW.plusSeconds(1));

        recordingRepository.save(recording);

        RecordingStream audio = streamRepository.find(recording.id(), StreamType.AUDIO)
                .orElseThrow();
        assertThat(audio.uploadId()).isNull();
        assertThat(audio.objectKey()).isNull();
        assertThat(audio.mime()).isNull();
        assertThat(audio.durationMs()).isNull();
        assertThat(audio.partSize()).isEqualTo(PART_SIZE);
        assertThat(audio.offsetMs()).isEqualTo(1_500L);
        assertThat(audio.status()).isEqualTo(StreamStatus.UPLOADING);
    }

    @Test
    void deleteByRecordingRemovesAllStreams() {
        SessionId sessionId = seedSession();
        Recording recording = freeRecording(sessionId);
        recordingRepository.save(recording);
        assertThat(streamRepository.findByRecording(recording.id())).hasSize(3);

        streamRepository.deleteByRecording(recording.id());

        assertThat(streamRepository.findByRecording(recording.id())).isEmpty();
        assertThat(streamRepository.find(recording.id(), StreamType.CAMERA)).isEmpty();
        assertThat(streamRowCount(recording.id())).isZero();
    }

    private Recording freeRecording(SessionId sessionId) {
        Recording recording = Recording.create(UUID.randomUUID(), sessionId, NOW);
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, StreamType.CAMERA,
                "upload-camera", CAMERA_KEY, PART_SIZE, 0L, "video/webm", NOW.plusSeconds(1));
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, StreamType.SCREEN,
                "upload-screen", SCREEN_KEY, PART_SIZE, 0L, "video/webm", NOW.plusSeconds(2));
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, StreamType.AUDIO,
                "upload-audio", AUDIO_KEY, PART_SIZE, 0L, "audio/webm", NOW.plusSeconds(3));
        return recording;
    }

    private int streamRowCount(UUID recordingId) {
        return jdbc.queryForObject(
                "select count(*) from m3_classroom.recording_streams where recording_id = ?",
                Integer.class, recordingId);
    }

    private SessionId seedSession() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO m3_classroom.tasks
                    (id, course_id, mode, topic, duration_recommended_seconds,
                     duration_max_seconds, created_by, status)
                VALUES (?, ?, 'free', '录制持久化测试', 900, 1200, ?, 'published')
                """, taskId.value(), TestFixtures.COURSE_ID, TestFixtures.TEACHER_ID);
        SessionId sessionId = new SessionId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO m3_classroom.sessions
                    (id, session_type, task_id, course_id, student_account_id, mode, status)
                VALUES (?, 'task', ?, ?, ?, 'free', 'in_progress')
                """, sessionId.value(), taskId.value(), TestFixtures.COURSE_ID,
                TestFixtures.STUDENT_ID);
        return sessionId;
    }
}
