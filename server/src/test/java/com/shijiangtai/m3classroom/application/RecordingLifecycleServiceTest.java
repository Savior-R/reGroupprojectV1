package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.CLASS;
import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER;
import static com.shijiangtai.m3classroom.TestFixtures.freeDraft;
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
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryRecordingObjectStorage;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.m3classroom.support.InMemorySubmissionRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecordingLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    private InMemorySessionRepository sessions;
    private InMemoryTaskRepository tasks;
    private InMemorySubmissionRepository submissions;
    private InMemoryRecordingRepository recordings;
    private CountingRecordingObjectStorage storage;
    private List<Runnable> afterCommitActions;
    private List<String> calls;
    private RecordingLifecycleService service;

    @BeforeEach
    void setUp() {
        sessions = new InMemorySessionRepository();
        tasks = new InMemoryTaskRepository();
        submissions = new InMemorySubmissionRepository();
        calls = new ArrayList<>();
        recordings = new InMemoryRecordingRepository(calls);
        storage = new CountingRecordingObjectStorage(calls);
        afterCommitActions = new ArrayList<>();
        service = new RecordingLifecycleService(
                recordings,
                new RecordingSessionRepository(sessions, calls),
                tasks,
                new RecordingSubmissionRepository(submissions, calls),
                storage,
                afterCommitActions::add,
                () -> NOW);
    }

    @Test
    void abortAllAbortsUploadingMultipartAndMarksRecordingDeleted() {
        SessionId sessionId = newSession();
        String objectKey = "recordings/school/task/session/camera.webm";
        String uploadId = storage.createMultipart(objectKey, "video/webm").uploadId();
        recordingWithUploadingStream(sessionId, objectKey, uploadId);

        // A repeated abort keeps the aggregate DELETED and re-registers the idempotent
        // best-effort object deletes, so a retried cleanup cannot strand objects.
        service.abortAll(sessionId);
        service.abortAll(sessionId);

        Recording recording = recordings.findBySession(sessionId).orElseThrow();
        assertThat(recording.status()).isEqualTo(RecordingStatus.DELETED);
        assertThat(recording.stream(StreamType.CAMERA).orElseThrow().status())
                .isEqualTo(StreamStatus.DELETED);
        assertThat(afterCommitActions).hasSize(2);
        assertThatThrownBy(() -> storage.presignPart(
                objectKey, uploadId, 1, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        afterCommitActions.forEach(Runnable::run);
        assertThatThrownBy(() -> storage.stat(objectKey))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void abortAllAbortsMultipartOfFailedStream() {
        SessionId sessionId = newSession();
        String objectKey = "recordings/school/task/session/camera.webm";
        String uploadId = storage.createMultipart(objectKey, "video/webm").uploadId();
        recordingWithFailedStream(sessionId, objectKey, uploadId);

        service.abortAll(sessionId);

        assertThatThrownBy(() -> storage.presignPart(
                objectKey, uploadId, 1, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void deleteAttemptAbortsMultipartOfFailedStream() {
        SessionId sessionId = newSession(
                new TaskId(UUID.randomUUID()), 1, SessionStatus.CANCELLED);
        String objectKey = keyFor(sessionId);
        String uploadId = storage.createMultipart(objectKey, "video/webm").uploadId();
        recordingWithFailedStream(sessionId, objectKey, uploadId);

        service.deleteAttempt(sessionId);

        assertThat(sessions.findById(sessionId)).isEmpty();
        assertThatThrownBy(() -> storage.presignPart(
                objectKey, uploadId, 1, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void deleteOtherAttemptsAbortsMultipartOfFailedStream() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        SessionId keep = newSession(taskId, 1);
        SessionId other = newSession(taskId, 2, SessionStatus.CANCELLED);
        String objectKey = keyFor(taskId, other);
        String uploadId = storage.createMultipart(objectKey, "video/webm").uploadId();
        recordingWithFailedStream(other, objectKey, uploadId);

        service.deleteOtherAttempts(student().accountId(), taskId, keep);

        assertThatThrownBy(() -> storage.presignPart(
                objectKey, uploadId, 1, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void abortAllDoesNotAbortUploadedMultipartButDeletesObject() {
        SessionId sessionId = newSession();
        String objectKey = keyFor(sessionId);
        String uploadId = storage.createMultipart(objectKey, "video/webm").uploadId();
        storage.recordObjectSize(objectKey, 1024);
        storage.completeMultipart(objectKey, uploadId, List.of(new PartEtag(1, "etag-1")));
        recordingWithUploadedStream(sessionId, objectKey, uploadId);
        int abortsBefore = storage.abortCalls();

        service.abortAll(sessionId);

        // An uploaded stream has no open multipart; aborting it would only log a false warning.
        assertThat(storage.abortCalls()).isEqualTo(abortsBefore);
        assertThat(afterCommitActions).hasSize(1);
        afterCommitActions.forEach(Runnable::run);
        assertThatThrownBy(() -> storage.stat(objectKey))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void deleteAttemptDoesNotAbortUploadedMultipartButDeletesObject() {
        SessionId sessionId = newSession(
                new TaskId(UUID.randomUUID()), 1, SessionStatus.CANCELLED);
        String objectKey = keyFor(sessionId);
        String uploadId = storage.createMultipart(objectKey, "video/webm").uploadId();
        storage.recordObjectSize(objectKey, 1024);
        storage.completeMultipart(objectKey, uploadId, List.of(new PartEtag(1, "etag-1")));
        recordingWithUploadedStream(sessionId, objectKey, uploadId);
        int abortsBefore = storage.abortCalls();

        service.deleteAttempt(sessionId);

        assertThat(storage.abortCalls()).isEqualTo(abortsBefore);
        assertThat(sessions.findById(sessionId)).isEmpty();
        assertThat(recordings.findBySession(sessionId)).isEmpty();
        assertThat(afterCommitActions).hasSize(1);
        afterCommitActions.forEach(Runnable::run);
        assertThatThrownBy(() -> storage.stat(objectKey))
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

    @Test
    void deleteExpiredTemporaryDeletesObjectsBeforeRows() {
        TaskId taskId = newOpenTask();
        SessionId sessionId = newEndedSession(taskId, NOW.minus(Duration.ofHours(25)));
        String cameraKey = keyFor(taskId, sessionId);
        String screenKey = screenKeyFor(taskId, sessionId);
        recordingWithCompletedObjects(sessionId, cameraKey, screenKey);

        assertThat(service.deleteExpiredTemporary(sessionId)).isTrue();

        assertThat(sessions.findById(sessionId)).isEmpty();
        assertThat(recordings.findBySession(sessionId)).isEmpty();
        assertThat(afterCommitActions).isEmpty();
        assertThat(calls).containsSubsequence(
                "storage.delete:" + cameraKey,
                "storage.delete:" + screenKey,
                "recording.delete",
                "session.delete");
        assertThatThrownBy(() -> storage.stat(cameraKey))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> storage.stat(screenKey))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void deleteExpiredTemporaryKeepsRowsWhenObjectDeleteFails() {
        TaskId taskId = newOpenTask();
        SessionId sessionId = newEndedSession(taskId, NOW.minus(Duration.ofHours(25)));
        String objectKey = keyFor(taskId, sessionId);
        recordingWithCompletedObjects(sessionId, objectKey);
        storage.failOnDelete(objectKey);

        assertThatThrownBy(() -> service.deleteExpiredTemporary(sessionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(objectKey);

        assertThat(sessions.findById(sessionId)).isPresent();
        assertThat(recordings.findBySession(sessionId)).isPresent();
        assertThat(afterCommitActions).isEmpty();
        assertThat(calls).doesNotContain("recording.delete", "session.delete");
    }

    @Test
    void deleteExpiredTemporarySkipsTaskCloseExtension() {
        TaskId taskId = newClosedTask(NOW.minus(Duration.ofHours(1)));
        SessionId sessionId = newEndedSession(taskId, NOW.minus(Duration.ofHours(25)));
        recordingWithCompletedObjects(sessionId, keyFor(taskId, sessionId));

        assertThat(service.deleteExpiredTemporary(sessionId)).isFalse();

        assertThat(sessions.findById(sessionId)).isPresent();
        assertThat(recordings.findBySession(sessionId)).isPresent();
        assertThat(calls).isEmpty();
    }

    @Test
    void deleteExpiredTemporaryUsesInclusiveTwentyFourHourBoundary() {
        TaskId taskId = newOpenTask();
        SessionId retained = newEndedSession(
                taskId, NOW.minus(Duration.ofHours(24)).plusSeconds(1));
        SessionId expired = newEndedSession(taskId, NOW.minus(Duration.ofHours(24)));

        assertThat(service.deleteExpiredTemporary(retained)).isFalse();
        assertThat(service.deleteExpiredTemporary(expired)).isTrue();

        assertThat(sessions.findById(retained)).isPresent();
        assertThat(sessions.findById(expired)).isEmpty();
    }

    @Test
    void deleteExpiredTemporaryRejectsSubmitted() {
        TaskId taskId = newOpenTask();
        SessionId sessionId = newSubmittedSession(taskId, NOW.minus(Duration.ofHours(25)));
        recordingWithCompletedObjects(sessionId, keyFor(taskId, sessionId));

        assertThat(service.deleteExpiredTemporary(sessionId)).isFalse();

        assertThat(sessions.findById(sessionId)).isPresent();
        assertThat(recordings.findBySession(sessionId)).isPresent();
        assertThat(calls).isEmpty();
    }

    @Test
    void deleteSubmittedSessionDeletesSubmissionBeforeSession() {
        TaskId taskId = newOpenTask();
        SessionId sessionId = newSubmittedSession(taskId, NOW.minus(Duration.ofHours(1)));
        submissions.save(Submission.create(
                UUID.randomUUID(), taskId, STUDENT, sessionId, CLASS,
                NOW.minus(Duration.ofHours(1))));
        String cameraKey = keyFor(taskId, sessionId);
        String screenKey = screenKeyFor(taskId, sessionId);
        recordingWithCompletedObjects(sessionId, cameraKey, screenKey);

        assertThat(service.deleteSubmittedSession(sessionId)).isTrue();

        assertThat(sessions.findById(sessionId)).isEmpty();
        assertThat(recordings.findBySession(sessionId)).isEmpty();
        assertThat(submissions.findBySession(sessionId)).isEmpty();
        assertThat(afterCommitActions).isEmpty();
        assertThat(calls).containsSubsequence(
                "storage.delete:" + cameraKey,
                "storage.delete:" + screenKey,
                "submission.delete",
                "recording.delete",
                "session.delete");
    }

    @Test
    void deleteSubmittedSessionKeepsRowsWhenObjectDeleteFails() {
        TaskId taskId = newOpenTask();
        SessionId sessionId = newSubmittedSession(taskId, NOW.minus(Duration.ofHours(1)));
        submissions.save(Submission.create(
                UUID.randomUUID(), taskId, STUDENT, sessionId, CLASS,
                NOW.minus(Duration.ofHours(1))));
        String objectKey = keyFor(taskId, sessionId);
        recordingWithCompletedObjects(sessionId, objectKey);
        storage.failOnDelete(objectKey);

        assertThatThrownBy(() -> service.deleteSubmittedSession(sessionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(objectKey);

        assertThat(sessions.findById(sessionId)).isPresent();
        assertThat(recordings.findBySession(sessionId)).isPresent();
        assertThat(submissions.findBySession(sessionId)).isPresent();
        assertThat(afterCommitActions).isEmpty();
        assertThat(calls).doesNotContain(
                "submission.delete", "recording.delete", "session.delete");
    }

    @Test
    void deleteSubmittedSessionSkipsNonSubmitted() {
        TaskId taskId = newOpenTask();
        SessionId sessionId = newEndedSession(taskId, NOW.minus(Duration.ofHours(25)));
        submissions.save(Submission.create(
                UUID.randomUUID(), taskId, STUDENT, sessionId, CLASS,
                NOW.minus(Duration.ofHours(1))));
        recordingWithCompletedObjects(sessionId, keyFor(taskId, sessionId));

        assertThat(service.deleteSubmittedSession(sessionId)).isFalse();

        assertThat(sessions.findById(sessionId)).isPresent();
        assertThat(recordings.findBySession(sessionId)).isPresent();
        assertThat(submissions.findBySession(sessionId)).isPresent();
        assertThat(calls).isEmpty();
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

    private TaskId newOpenTask() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        tasks.save(Task.createDraft(
                taskId, COURSE, TEACHER, freeDraft(), NOW.minus(Duration.ofDays(3))));
        return taskId;
    }

    private TaskId newClosedTask(Instant closedAt) {
        TaskId taskId = new TaskId(UUID.randomUUID());
        tasks.save(Task.rehydrate(
                taskId, COURSE, TEACHER, freeDraft(), TaskStatus.CLOSED,
                null, null, 1, closedAt.minus(Duration.ofDays(2)), closedAt,
                closedAt.minus(Duration.ofDays(3)), closedAt));
        return taskId;
    }

    private SessionId newEndedSession(TaskId taskId, Instant endedAt) {
        return persistedSession(taskId, SessionStatus.ENDED, endedAt);
    }

    private SessionId newSubmittedSession(TaskId taskId, Instant endedAt) {
        return persistedSession(taskId, SessionStatus.SUBMITTED, endedAt);
    }

    private SessionId persistedSession(TaskId taskId, SessionStatus status, Instant endedAt) {
        Session session = Session.rehydrate(
                new SessionId(UUID.randomUUID()), SessionType.TASK, taskId, COURSE, STUDENT,
                TaskMode.FREE, status, 1, endedAt.minus(Duration.ofMinutes(10)), endedAt,
                Duration.ofMinutes(10).toMillis(), Map.of(), endedAt, endedAt);
        sessions.save(session);
        return session.id();
    }

    private static String screenKeyFor(TaskId taskId, SessionId sessionId) {
        return "recordings/school/task/" + taskId.value() + "/" + sessionId.value()
                + "/screen.webm";
    }

    private static String keyFor(TaskId taskId, SessionId sessionId) {
        return "recordings/school/task/" + taskId.value() + "/" + sessionId.value()
                + "/camera.webm";
    }

    private static String keyFor(SessionId sessionId) {
        return "recordings/school/task/task/" + sessionId.value() + "/camera.webm";
    }

    private void recordingWithFailedStream(
            SessionId sessionId, String objectKey, String uploadId) {
        Recording recording = Recording.create(UUID.randomUUID(), sessionId, NOW);
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, StreamType.CAMERA,
                uploadId, objectKey, RecordingStream.DEFAULT_PART_SIZE, 0, "video/webm", NOW);
        recording.abortStream(StreamType.CAMERA, NOW);
        recordings.save(recording);
    }

    private void recordingWithUploadingStream(
            SessionId sessionId, String objectKey, String uploadId) {
        Recording recording = Recording.create(UUID.randomUUID(), sessionId, NOW);
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, StreamType.CAMERA,
                uploadId, objectKey, RecordingStream.DEFAULT_PART_SIZE, 0, "video/webm", NOW);
        recordings.save(recording);
    }

    private void recordingWithUploadedStream(
            SessionId sessionId, String objectKey, String uploadId) {
        Recording recording = Recording.create(UUID.randomUUID(), sessionId, NOW);
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, StreamType.CAMERA,
                uploadId, objectKey, RecordingStream.DEFAULT_PART_SIZE, 0, "video/webm", NOW);
        recording.markStreamUploaded(
                StreamType.CAMERA, TaskMode.FREE, 1, 1024, 5_000, "video/webm", NOW);
        recordings.save(recording);
    }

    private void recordingWithCompletedObjects(SessionId sessionId, String... objectKeys) {
        Recording recording = Recording.create(UUID.randomUUID(), sessionId, NOW);
        StreamType[] types = {StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO};
        for (int index = 0; index < objectKeys.length; index++) {
            String objectKey = objectKeys[index];
            String uploadId = storage.createMultipart(objectKey, "video/webm").uploadId();
            storage.recordObjectSize(objectKey, 1024);
            storage.completeMultipart(
                    objectKey, uploadId, List.of(new PartEtag(1, "etag-1")));
            recording.startStream(UUID.randomUUID(), TaskMode.FREE, types[index],
                    uploadId, objectKey, RecordingStream.DEFAULT_PART_SIZE, 0,
                    "video/webm", NOW);
        }
        recordings.save(recording);
    }

    /** Counts multipart abort attempts so cleanup can assert it never aborts an uploaded stream. */
    private static final class CountingRecordingObjectStorage
            extends InMemoryRecordingObjectStorage {
        private final List<String> calls;
        private final Set<String> failingKeys = new HashSet<>();
        private int abortCalls;

        private CountingRecordingObjectStorage(List<String> calls) {
            this.calls = calls;
        }

        private void failOnDelete(String objectKey) {
            failingKeys.add(objectKey);
        }

        @Override
        public void delete(String objectKey) {
            calls.add("storage.delete:" + objectKey);
            if (failingKeys.contains(objectKey)) {
                throw new IllegalStateException("object delete failed: " + objectKey);
            }
            super.delete(objectKey);
        }

        @Override
        public void abortMultipart(String objectKey, String uploadId) {
            abortCalls++;
            super.abortMultipart(objectKey, uploadId);
        }

        private int abortCalls() {
            return abortCalls;
        }
    }

    private static final class RecordingSessionRepository implements SessionRepository {
        private final SessionRepository delegate;
        private final List<String> calls;

        private RecordingSessionRepository(SessionRepository delegate, List<String> calls) {
            this.delegate = delegate;
            this.calls = calls;
        }

        @Override
        public Session save(Session session) {
            return delegate.save(session);
        }

        @Override
        public Optional<Session> findById(SessionId id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Session> findByIdForUpdate(SessionId id) {
            return delegate.findByIdForUpdate(id);
        }

        @Override
        public Optional<Session> findActiveByStudentAndTask(
                AccountId studentAccountId, TaskId taskId) {
            return delegate.findActiveByStudentAndTask(studentAccountId, taskId);
        }

        @Override
        public List<Session> findByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
            return delegate.findByStudentAndTask(studentAccountId, taskId);
        }

        @Override
        public List<Session> findByStatusIn(Collection<SessionStatus> statuses) {
            return delegate.findByStatusIn(statuses);
        }

        @Override
        public long countByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
            return delegate.countByStudentAndTask(studentAccountId, taskId);
        }

        @Override
        public List<Session> findExpiredTemporary(Instant now) {
            return delegate.findExpiredTemporary(now);
        }

        @Override
        public void deleteById(SessionId id) {
            calls.add("session.delete");
            delegate.deleteById(id);
        }
    }

    private static final class RecordingSubmissionRepository implements SubmissionRepository {
        private final SubmissionRepository delegate;
        private final List<String> calls;

        private RecordingSubmissionRepository(
                SubmissionRepository delegate, List<String> calls) {
            this.delegate = delegate;
            this.calls = calls;
        }

        @Override
        public Submission save(Submission submission) {
            return delegate.save(submission);
        }

        @Override
        public Optional<Submission> findByTaskAndStudent(
                TaskId taskId, AccountId studentAccountId) {
            return delegate.findByTaskAndStudent(taskId, studentAccountId);
        }

        @Override
        public Optional<Submission> findBySession(SessionId sessionId) {
            return delegate.findBySession(sessionId);
        }

        @Override
        public boolean existsBySession(SessionId sessionId) {
            return delegate.existsBySession(sessionId);
        }

        @Override
        public List<Submission> findExpiredForRetention(Instant now) {
            return delegate.findExpiredForRetention(now);
        }

        @Override
        public void deleteBySession(SessionId sessionId) {
            calls.add("submission.delete");
            delegate.deleteBySession(sessionId);
        }
    }

    private static final class InMemoryRecordingRepository implements RecordingRepository {
        private final Map<SessionId, Recording> bySession = new LinkedHashMap<>();
        private final List<String> calls;

        private InMemoryRecordingRepository(List<String> calls) {
            this.calls = calls;
        }

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
            calls.add("recording.delete");
            bySession.remove(sessionId);
        }
    }
}
