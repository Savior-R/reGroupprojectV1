package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.PartEtag;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStatus;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryRecordingObjectStorage;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecordingLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    private InMemorySessionRepository sessions;
    private InMemoryRecordingRepository recordings;
    private InMemoryRecordingObjectStorage storage;
    private List<Runnable> afterCommitActions;
    private RecordingLifecycleService service;

    @BeforeEach
    void setUp() {
        sessions = new InMemorySessionRepository();
        recordings = new InMemoryRecordingRepository();
        storage = new InMemoryRecordingObjectStorage();
        afterCommitActions = new ArrayList<>();
        service = new RecordingLifecycleService(
                recordings, sessions, storage, afterCommitActions::add, () -> NOW);
    }

    @Test
    void abortAllAbortsUploadingMultipartAndMarksStreamFailed() {
        SessionId sessionId = newSession();
        String objectKey = "recordings/school/task/session/camera.webm";
        String uploadId = storage.createMultipart(objectKey, "video/webm").uploadId();
        recordingWithUploadingStream(sessionId, objectKey, uploadId);

        service.abortAll(sessionId);
        service.abortAll(sessionId);

        Recording recording = recordings.findBySession(sessionId).orElseThrow();
        assertThat(recording.status()).isEqualTo(RecordingStatus.FAILED);
        assertThat(recording.stream(StreamType.CAMERA).orElseThrow().status())
                .isEqualTo(StreamStatus.FAILED);
        assertThatThrownBy(() -> storage.presignPart(
                objectKey, uploadId, 1, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void abortAllWithoutRecordingIsNoOp() {
        SessionId sessionId = newSession();

        assertThatCode(() -> service.abortAll(sessionId)).doesNotThrowAnyException();
    }

    @Test
    void deleteOtherAttemptsSchedulesObjectDeletesAndRemovesOtherSessions() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        SessionId keep = newSession(taskId, 1);
        SessionId other = newSession(taskId, 2, SessionStatus.CANCELLED);
        String otherKey = "recordings/school/task/" + taskId.value() + "/" + other.value()
                + "/camera.webm";
        String uploadId = storage.createMultipart(otherKey, "video/webm").uploadId();
        storage.recordObjectSize(otherKey, 1024);
        storage.completeMultipart(otherKey, uploadId, List.of(new PartEtag(1, "etag-1")));
        recordingWithUploadingStream(other, otherKey, uploadId);

        service.deleteOtherAttempts(student().accountId(), taskId, keep);

        assertThat(sessions.findById(other)).isEmpty();
        assertThat(sessions.findById(keep)).isPresent();
        assertThat(recordings.findBySession(other)).isEmpty();
        assertThat(afterCommitActions).hasSize(1);
        assertThat(storage.stat(otherKey).size()).isEqualTo(1024);

        afterCommitActions.forEach(Runnable::run);

        assertThatThrownBy(() -> storage.stat(otherKey))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void deleteOtherAttemptsNeverDeletesKeptSession() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        SessionId keep = newSession(taskId, 1);

        service.deleteOtherAttempts(student().accountId(), taskId, keep);

        assertThat(sessions.findById(keep)).isPresent();
        assertThat(afterCommitActions).isEmpty();
    }

    @Test
    void deleteOtherAttemptsSkipsSubmittedAndActiveAttempts() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        SessionId keep = newSession(taskId, 1, SessionStatus.SUBMITTED);
        SessionId submitted = newSession(taskId, 2, SessionStatus.SUBMITTED);
        SessionId active = newSession(taskId, 3, SessionStatus.IN_PROGRESS);
        SessionId cancelled = newSession(taskId, 4, SessionStatus.CANCELLED);
        String submittedKey = keyFor(taskId, submitted);
        String activeKey = keyFor(taskId, active);
        String cancelledKey = keyFor(taskId, cancelled);
        recordingWithUploadingStream(
                submitted, submittedKey, storage.createMultipart(
                        submittedKey, "video/webm").uploadId());
        recordingWithUploadingStream(
                active, activeKey, storage.createMultipart(
                        activeKey, "video/webm").uploadId());
        recordingWithUploadingStream(
                cancelled, cancelledKey, storage.createMultipart(
                        cancelledKey, "video/webm").uploadId());

        service.deleteOtherAttempts(student().accountId(), taskId, keep);

        assertThat(sessions.findById(keep)).isPresent();
        assertThat(sessions.findById(submitted)).isPresent();
        assertThat(sessions.findById(active)).isPresent();
        assertThat(sessions.findById(cancelled)).isEmpty();
        assertThat(recordings.findBySession(submitted)).isPresent();
        assertThat(recordings.findBySession(active)).isPresent();
        assertThat(recordings.findBySession(cancelled)).isEmpty();
        assertThat(afterCommitActions).hasSize(1);
    }

    @Test
    void deleteOtherAttemptsAbortsUploadingMultipart() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        SessionId keep = newSession(taskId, 1);
        SessionId other = newSession(taskId, 2, SessionStatus.CANCELLED);
        String objectKey = keyFor(taskId, other);
        String uploadId = storage.createMultipart(objectKey, "video/webm").uploadId();
        recordingWithUploadingStream(other, objectKey, uploadId);

        service.deleteOtherAttempts(student().accountId(), taskId, keep);

        assertThatThrownBy(() -> storage.presignPart(
                objectKey, uploadId, 1, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    private SessionId newSession() {
        return newSession(new TaskId(UUID.randomUUID()), 1, SessionStatus.PREPARING);
    }

    private SessionId newSession(TaskId taskId, int attemptNo) {
        return newSession(taskId, attemptNo, SessionStatus.PREPARING);
    }

    private SessionId newSession(TaskId taskId, int attemptNo, SessionStatus status) {
        Session session = Session.createPreparing(
                new SessionId(UUID.randomUUID()), SessionType.TASK, taskId,
                COURSE, STUDENT, TaskMode.FREE, attemptNo, NOW);
        switch (status) {
            case PREPARING -> {
            }
            case IN_PROGRESS -> session.start(NOW);
            case CANCELLED -> session.cancel(NOW);
            case ENDED -> {
                session.start(NOW);
                session.end(NOW);
            }
            case SUBMITTED -> {
                session.start(NOW);
                session.end(NOW);
                session.submit(NOW);
            }
            default -> throw new IllegalArgumentException("unsupported test status: " + status);
        }
        sessions.save(session);
        return session.id();
    }

    private static String keyFor(TaskId taskId, SessionId sessionId) {
        return "recordings/school/task/" + taskId.value() + "/" + sessionId.value()
                + "/camera.webm";
    }

    private void recordingWithUploadingStream(
            SessionId sessionId, String objectKey, String uploadId) {
        Recording recording = Recording.create(UUID.randomUUID(), sessionId, NOW);
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, StreamType.CAMERA,
                uploadId, objectKey, RecordingStream.DEFAULT_PART_SIZE, 0, "video/webm", NOW);
        recordings.save(recording);
    }

    private static final class InMemoryRecordingRepository implements RecordingRepository {
        private final Map<SessionId, Recording> bySession = new LinkedHashMap<>();

        @Override
        public Recording save(Recording recording) {
            bySession.put(recording.sessionId(), recording);
            return recording;
        }

        @Override
        public Optional<Recording> findBySession(SessionId sessionId) {
            return Optional.ofNullable(bySession.get(sessionId));
        }

        @Override
        public Optional<Recording> findById(UUID id) {
            return bySession.values().stream()
                    .filter(recording -> recording.id().equals(id))
                    .findFirst();
        }

        @Override
        public void deleteBySession(SessionId sessionId) {
            bySession.remove(sessionId);
        }
    }
}
