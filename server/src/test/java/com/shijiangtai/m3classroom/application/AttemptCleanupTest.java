package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.CLASS;
import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.NOW;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER;
import static com.shijiangtai.m3classroom.TestFixtures.course;
import static com.shijiangtai.m3classroom.TestFixtures.coursePort;
import static com.shijiangtai.m3classroom.TestFixtures.freeDraft;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.ContentRequest;
import com.shijiangtai.m3classroom.application.port.ContentResponse;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.PartEtag;
import com.shijiangtai.m3classroom.application.view.SessionView;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStatus;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryRecordingObjectStorage;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.m3classroom.support.FakeScheduler;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.m3classroom.support.InMemorySessionTextStore;
import com.shijiangtai.m3classroom.support.InMemorySubmissionRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskAssignmentRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Temporary-attempt cap and cancellation cleanup (05B §6, plan Task 7).
 */
class AttemptCleanupTest {

    private static final long OBJECT_SIZE = 1024L;

    private InMemoryTaskRepository tasks;
    private InMemoryTaskAssignmentRepository assignments;
    private InMemorySessionRepository sessions;
    private InMemoryRecordingRepository recordings;
    private InMemoryRecordingObjectStorage storage;
    private List<Runnable> afterCommitActions;
    private RecordingLifecycleService lifecycle;
    private SessionApplicationService service;
    private TaskId taskId;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        assignments = new InMemoryTaskAssignmentRepository();
        FakeCourseQueryPort courses = coursePort();
        sessions = new InMemorySessionRepository();
        InMemorySessionEventStore events = new InMemorySessionEventStore();
        InMemorySessionTextStore texts = new InMemorySessionTextStore();
        InMemorySubmissionRepository submissions = new InMemorySubmissionRepository();
        FakeScheduler scheduler = new FakeScheduler();
        recordings = new InMemoryRecordingRepository();
        storage = new InMemoryRecordingObjectStorage();
        afterCommitActions = new ArrayList<>();
        lifecycle = new RecordingLifecycleService(
                recordings, sessions, storage, afterCommitActions::add, () -> NOW);

        SessionRuntimeRegistry registry = new SessionRuntimeRegistry(
                sessions, tasks, events, texts, UUID::randomUUID, () -> NOW, scheduler,
                unavailableContent());
        service = new SessionApplicationServiceImpl(
                tasks, assignments, courses, new TaskAccessPolicy(courses),
                new SessionAccessPolicy(courses, submissions), sessions, events,
                mock(SubmissionService.class), registry, lifecycle,
                new SessionStartGuard(sessions, tasks, courses, () -> NOW),
                UUID::randomUUID, () -> NOW);
        taskId = createPublishedTask();
    }

    @Test
    void sixthAttemptDeletesOldestTemporarySession() {
        List<SessionId> attempts = new ArrayList<>();
        for (int attemptNo = 1; attemptNo <= 5; attemptNo++) {
            attempts.add(seedAttempt(attemptNo, SessionStatus.CANCELLED));
        }
        String oldestKey = objectKey(attempts.get(0));
        recordingWithUploadedStream(attempts.get(0), oldestKey);
        String newerKey = objectKey(attempts.get(2));
        recordingWithUploadedStream(attempts.get(2), newerKey);

        SessionView created = service.createSession(student(), taskId);

        assertThat(sessions.countByStudentAndTask(STUDENT, taskId)).isEqualTo(5);
        assertThat(sessions.findById(attempts.get(0))).isEmpty();
        assertThat(recordings.findBySession(attempts.get(0))).isEmpty();
        assertThat(sessions.findById(attempts.get(1))).isPresent();
        assertThat(sessions.findById(attempts.get(2))).isPresent();
        assertThat(recordings.findBySession(attempts.get(2))).isPresent();
        assertThat(afterCommitActions).hasSize(1);

        // The objects survive until the transaction commits; only the oldest is scheduled.
        assertThat(storage.stat(oldestKey).size()).isEqualTo(OBJECT_SIZE);
        assertThat(storage.stat(newerKey).size()).isEqualTo(OBJECT_SIZE);

        afterCommitActions.forEach(Runnable::run);

        assertThatThrownBy(() -> storage.stat(oldestKey))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(storage.stat(newerKey).size()).isEqualTo(OBJECT_SIZE);
        assertThat(created.status()).isEqualTo("preparing");
    }

    @Test
    void submittedAttemptIsNeverDeleted() {
        SessionId submitted = seedAttempt(1, SessionStatus.SUBMITTED);
        String submittedKey = objectKey(submitted);
        recordingWithUploadedStream(submitted, submittedKey);
        SessionId oldestCancelled = seedAttempt(2, SessionStatus.CANCELLED);
        String cancelledKey = objectKey(oldestCancelled);
        recordingWithUploadedStream(oldestCancelled, cancelledKey);
        for (int attemptNo = 3; attemptNo <= 6; attemptNo++) {
            seedAttempt(attemptNo, SessionStatus.CANCELLED);
        }

        SessionView created = service.createSession(student(), taskId);

        assertThat(sessions.findById(submitted)).isPresent();
        assertThat(recordings.findBySession(submitted)).isPresent();
        assertThat(sessions.findById(oldestCancelled)).isEmpty();
        assertThat(recordings.findBySession(oldestCancelled)).isEmpty();
        assertThat(afterCommitActions).hasSize(1);

        afterCommitActions.forEach(Runnable::run);

        assertThat(storage.stat(submittedKey).size()).isEqualTo(OBJECT_SIZE);
        assertThatThrownBy(() -> storage.stat(cancelledKey))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        // An active attempt is guarded even when deleteAttempt is called directly.
        SessionId active = new SessionId(created.sessionId());
        lifecycle.deleteAttempt(active);
        assertThat(sessions.findById(active)).isPresent();
        assertThat(afterCommitActions).hasSize(1);
    }

    @Test
    void cancelAbortsUploadingMultipartAndDeletesObjects() {
        SessionView created = service.createSession(student(), taskId);
        SessionId sessionId = new SessionId(created.sessionId());
        String cameraKey = objectKey(sessionId);
        String cameraUploadId =
                storage.createMultipart(cameraKey, "video/webm").uploadId();
        String screenKey = objectKey(sessionId);
        String screenUploadId =
                storage.createMultipart(screenKey, "video/webm").uploadId();
        storage.recordObjectSize(screenKey, OBJECT_SIZE);
        storage.completeMultipart(
                screenKey, screenUploadId, List.of(new PartEtag(1, "etag-1")));

        Recording recording = Recording.create(UUID.randomUUID(), sessionId, NOW);
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, StreamType.CAMERA,
                cameraUploadId, cameraKey, RecordingStream.DEFAULT_PART_SIZE, 0,
                "video/webm", NOW);
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, StreamType.SCREEN,
                screenUploadId, screenKey, RecordingStream.DEFAULT_PART_SIZE, 0,
                "video/webm", NOW);
        recording.markStreamUploaded(
                StreamType.SCREEN, TaskMode.FREE, 1, OBJECT_SIZE, 1_000L, "video/webm", NOW);
        recordings.save(recording);

        service.cancel(student(), sessionId);

        assertThat(sessions.findById(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.CANCELLED);
        Recording stored = recordings.findBySession(sessionId).orElseThrow();
        assertThat(stored.status()).isEqualTo(RecordingStatus.DELETED);
        assertThat(stored.streams()).extracting(RecordingStream::status)
                .containsOnly(StreamStatus.DELETED);
        assertThatThrownBy(() -> storage.presignPart(
                cameraKey, cameraUploadId, 1, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(afterCommitActions).hasSize(1);
        assertThat(storage.stat(screenKey).size()).isEqualTo(OBJECT_SIZE);

        afterCommitActions.forEach(Runnable::run);

        assertThatThrownBy(() -> storage.stat(screenKey))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    private SessionId seedAttempt(int attemptNo, SessionStatus status) {
        Session session = Session.createPreparing(
                new SessionId(UUID.randomUUID()), SessionType.TASK, taskId,
                COURSE, STUDENT, TaskMode.FREE, attemptNo, NOW);
        switch (status) {
            case PREPARING -> {
            }
            case IN_PROGRESS -> session.start(NOW);
            case ENDED -> {
                session.start(NOW);
                session.end(NOW);
            }
            case REVIEWING -> {
                session.start(NOW);
                session.end(NOW);
                session.markReviewing(NOW);
            }
            case SUBMITTED -> {
                session.start(NOW);
                session.end(NOW);
                session.submit(NOW);
            }
            case CANCELLED -> session.cancel(NOW);
            case FAILED -> session.fail(FailureReason.RECORDING_LOST, NOW);
        }
        sessions.save(session);
        return session.id();
    }

    private void recordingWithUploadedStream(SessionId sessionId, String objectKey) {
        String uploadId = storage.createMultipart(objectKey, "video/webm").uploadId();
        storage.recordObjectSize(objectKey, OBJECT_SIZE);
        storage.completeMultipart(objectKey, uploadId, List.of(new PartEtag(1, "etag-1")));
        Recording recording = Recording.create(UUID.randomUUID(), sessionId, NOW);
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, StreamType.CAMERA,
                uploadId, objectKey, RecordingStream.DEFAULT_PART_SIZE, 0,
                "video/webm", NOW);
        recording.markStreamUploaded(
                StreamType.CAMERA, TaskMode.FREE, 1, OBJECT_SIZE, 1_000L, "video/webm", NOW);
        recordings.save(recording);
    }

    private String objectKey(SessionId sessionId) {
        return "recordings/school/task/" + taskId.value() + "/" + sessionId.value()
                + "/camera-" + UUID.randomUUID() + ".webm";
    }

    private TaskId createPublishedTask() {
        Task task = Task.createDraft(
                new TaskId(UUID.randomUUID()), COURSE, TEACHER, freeDraft(), NOW);
        TaskSnapshot snapshot = new TaskSnapshotFactory().create(
                task, course(), List.of(), NOW);
        task.publish(snapshot, NOW);
        tasks.save(task);
        assignments.saveAll(List.of(new TaskAssignment(
                UUID.randomUUID(), task.id(), STUDENT, CLASS, NOW,
                AssignmentStatus.ASSIGNED)));
        return task.id();
    }

    private static ContentClient unavailableContent() {
        return new ContentClient() {
            @Override
            public ContentResponse question(ContentRequest request) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "内容服务不可用");
            }

            @Override
            public ContentResponse answer(ContentRequest request) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "内容服务不可用");
            }
        };
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
