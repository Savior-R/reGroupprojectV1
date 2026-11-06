package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.CLASS;
import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER;
import static com.shijiangtai.m3classroom.TestFixtures.course;
import static com.shijiangtai.m3classroom.TestFixtures.coursePort;
import static com.shijiangtai.m3classroom.TestFixtures.freeDraft;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.student2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shijiangtai.m3classroom.application.port.PersistenceFlushException;
import com.shijiangtai.m3classroom.application.port.RecordingLifecyclePort;
import com.shijiangtai.m3classroom.application.view.SubmissionView;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStatus;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.submission.SubmissionCreatedEvent;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.m3classroom.support.InMemorySubmissionRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskAssignmentRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.m3classroom.support.RecordingEventPublisher;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant END = NOW.plusSeconds(60);

    private InMemorySessionRepository sessions;
    private InMemoryTaskRepository tasks;
    private InMemoryTaskAssignmentRepository assignments;
    private InMemorySubmissionRepository submissions;
    private InMemoryRecordingRepository recordings;
    private InMemorySessionEventStore events;
    private RecordingEventPublisher publisher;
    private RecordingLifecycleRecorder lifecycle;
    private SessionRuntimeRegistry registry;
    private SubmissionService service;

    @BeforeEach
    void setUp() {
        sessions = new InMemorySessionRepository();
        tasks = new InMemoryTaskRepository();
        assignments = new InMemoryTaskAssignmentRepository();
        submissions = new InMemorySubmissionRepository();
        recordings = new InMemoryRecordingRepository();
        events = new InMemorySessionEventStore();
        publisher = new RecordingEventPublisher();
        lifecycle = new RecordingLifecycleRecorder();
        registry = mock(SessionRuntimeRegistry.class);
        when(registry.find(any())).thenReturn(Optional.empty());
        service = newService(submissions);
    }

    private SubmissionService newService(SubmissionRepository submissionRepository) {
        return new SubmissionServiceImpl(
                sessions,
                new SessionAccessPolicy(coursePort(), submissionRepository),
                registry,
                recordings,
                tasks,
                assignments,
                submissionRepository,
                events,
                publisher,
                lifecycle,
                UUID::randomUUID,
                () -> NOW);
    }

    @Test
    void submitRequiresEndedOrReviewingSession() {
        TaskId taskId = createPublishedTask();
        for (SessionStatus status : List.of(
                SessionStatus.PREPARING,
                SessionStatus.IN_PROGRESS,
                SessionStatus.CANCELLED)) {
            Session session = newSession(taskId, status);
            uploadAll(session.id());

            assertCode(() -> service.submit(student(), session.id()),
                    ErrorCode.SESSION_STATE_INVALID);
            assertThat(submissions.size()).isZero();
        }
    }

    @Test
    void submitRequiresAllRequiredStreamsUploaded() {
        TaskId taskId = createPublishedTask();
        Session session = newSession(taskId, SessionStatus.ENDED);
        upload(session.id(), StreamType.CAMERA);

        assertThatThrownBy(() -> service.submit(student(), session.id()))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.RECORDING_INCOMPLETE);
                    assertThat(exception.details()).containsEntry(
                            "missing_streams", List.of("screen", "audio"));
                });
        assertThat(submissions.size()).isZero();
        assertThat(sessions.findById(session.id()).orElseThrow().status())
                .isEqualTo(SessionStatus.ENDED);
        assertThat(events.after(session.id(), 0, 100)).isEmpty();
        assertThat(publisher.events()).isEmpty();
        assertThat(lifecycle.cleanups()).isEmpty();
    }

    @Test
    void submitRejectsUploadedButEmptyStream() {
        TaskId taskId = createPublishedTask();
        Session session = newSession(taskId, SessionStatus.ENDED);
        UUID recordingId = UUID.randomUUID();
        recordings.save(Recording.rehydrate(
                recordingId, session.id(), RecordingStatus.UPLOADED, List.of(
                        uploadedStream(recordingId, StreamType.CAMERA, 0L, 5_000L),
                        uploadedStream(recordingId, StreamType.SCREEN, 1_024L, null),
                        uploadedStream(recordingId, StreamType.AUDIO, 1_024L, 5_000L)),
                NOW, NOW));

        assertThatThrownBy(() -> service.submit(student(), session.id()))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.RECORDING_INCOMPLETE);
                    assertThat(exception.details()).containsEntry(
                            "invalid_streams", List.of("camera", "screen"));
                });
        assertThat(submissions.size()).isZero();
        assertThat(sessions.findById(session.id()).orElseThrow().status())
                .isEqualTo(SessionStatus.ENDED);
    }

    @Test
    void submitCreatesSubmissionAndMarksSessionAndAssignment() {
        TaskId taskId = createPublishedTask();
        Session session = newSession(taskId, SessionStatus.ENDED);
        uploadAll(session.id());

        SubmissionView view = submitAndCleanup(student(), session.id());

        assertThat(view.submissionId()).isNotNull();
        assertThat(view.taskId()).isEqualTo(taskId.value());
        assertThat(view.sessionId()).isEqualTo(session.id().value());
        assertThat(view.studentAccountId()).isEqualTo(STUDENT.value());
        assertThat(view.status()).isEqualTo("submitted");
        assertThat(view.submittedAt()).isEqualTo(NOW.toString());

        Session persisted = sessions.findById(session.id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(SessionStatus.SUBMITTED);
        assertThat(persisted.updatedAt()).isEqualTo(NOW);
        assertThat(assignments.find(taskId, STUDENT).orElseThrow().status())
                .isEqualTo(AssignmentStatus.SUBMITTED);

        Submission submission = submissions.findBySession(session.id()).orElseThrow();
        assertThat(submission.id()).isEqualTo(view.submissionId());
        assertThat(submission.taskId()).isEqualTo(taskId);
        assertThat(submission.studentAccountId()).isEqualTo(STUDENT);
        assertThat(submission.classIdSnapshot()).isEqualTo(CLASS);
        assertThat(submission.submittedAt()).isEqualTo(NOW);
        assertThat(submission.status()).isEqualTo("submitted");

        List<SessionEvent> stored = events.after(session.id(), 0, 100);
        assertThat(stored).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("submission.created");
            assertThat(event.seq()).isEqualTo(1);
            assertThat(event.atMs()).isEqualTo(60_000L);
            assertThat(event.payload()).containsExactly(Map.entry(
                    "submission_id", view.submissionId().toString()));
        });

        assertThat(publisher.events()).singleElement().satisfies(published -> {
            SubmissionCreatedEvent event = (SubmissionCreatedEvent) published;
            assertThat(event.submissionId()).isEqualTo(view.submissionId());
            assertThat(event.taskId()).isEqualTo(taskId.value());
            assertThat(event.sessionId()).isEqualTo(session.id().value());
            assertThat(event.studentAccountId()).isEqualTo(STUDENT.value());
            assertThat(event.classIdSnapshot()).isEqualTo(CLASS.value());
            assertThat(event.submittedAt()).isEqualTo(NOW);
        });

        assertThat(lifecycle.cleanups()).containsExactly(
                new CleanupCall(STUDENT, taskId, session.id()));
    }

    @Test
    void submitAcceptsReviewingSession() {
        TaskId taskId = createPublishedTask();
        Session session = newSession(taskId, SessionStatus.REVIEWING);
        uploadAll(session.id());

        SubmissionView view = submitAndCleanup(student(), session.id());

        assertThat(view.status()).isEqualTo("submitted");
        assertThat(sessions.findById(session.id()).orElseThrow().status())
                .isEqualTo(SessionStatus.SUBMITTED);
        assertThat(submissions.findBySession(session.id())).isPresent();
        assertThat(events.after(session.id(), 0, 100)).hasSize(1);
        assertThat(lifecycle.cleanups()).hasSize(1);
    }

    @Test
    void submitTwiceFails() {
        TaskId taskId = createPublishedTask();
        Session session = newSession(taskId, SessionStatus.ENDED);
        uploadAll(session.id());
        submitAndCleanup(student(), session.id());

        assertCode(() -> service.submit(student(), session.id()),
                ErrorCode.SESSION_STATE_INVALID);
        assertThat(submissions.size()).isEqualTo(1);
        assertThat(events.after(session.id(), 0, 100)).hasSize(1);
        assertThat(lifecycle.cleanups()).hasSize(1);
    }

    @Test
    void submitRejectsExistingTaskSubmission() {
        TaskId taskId = createPublishedTask();
        Session previous = newSession(taskId, SessionStatus.CANCELLED);
        submissions.save(Submission.create(
                UUID.randomUUID(), taskId, STUDENT, previous.id(), CLASS, NOW));
        Session session = newSession(taskId, SessionStatus.ENDED);
        uploadAll(session.id());

        assertCode(() -> service.submit(student(), session.id()),
                ErrorCode.SESSION_STATE_INVALID);
        assertThat(submissions.size()).isEqualTo(1);
        assertThat(sessions.findById(session.id()).orElseThrow().status())
                .isEqualTo(SessionStatus.ENDED);
    }

    @Test
    void submitDeletesOtherAttempts() {
        TaskId taskId = createPublishedTask();
        Session other = newSession(taskId, SessionStatus.CANCELLED);
        uploadAll(other.id());
        Session keep = newSession(taskId, SessionStatus.ENDED);
        uploadAll(keep.id());

        // The facade performs this call only after submit's transaction has committed.
        submitAndCleanup(student(), keep.id());

        assertThat(lifecycle.cleanups()).containsExactly(
                new CleanupCall(STUDENT, taskId, keep.id()));
        assertThat(recordings.findBySession(keep.id())).isPresent();
    }

    @Test
    void duplicateConstraintViolationMapsToSessionStateInvalid() {
        TaskId taskId = createPublishedTask();
        Session session = newSession(taskId, SessionStatus.ENDED);
        uploadAll(session.id());
        SubmissionRepository failing = new SubmissionRepository() {
            @Override
            public Submission save(Submission submission) {
                throw new DataIntegrityViolationException("duplicate submission");
            }

            @Override
            public Optional<Submission> findByTaskAndStudent(
                    TaskId task, AccountId studentAccountId) {
                return submissions.findByTaskAndStudent(task, studentAccountId);
            }

            @Override
            public Optional<Submission> findBySession(SessionId sessionId) {
                return submissions.findBySession(sessionId);
            }

            @Override
            public boolean existsBySession(SessionId sessionId) {
                return submissions.existsBySession(sessionId);
            }
        };

        assertCode(() -> newService(failing).submit(student(), session.id()),
                ErrorCode.SESSION_STATE_INVALID);
        assertThat(submissions.size()).isZero();
    }

    @Test
    void submitFlushFailureLeavesNoSubmission() {
        TaskId taskId = createPublishedTask();
        Session session = newSession(taskId, SessionStatus.ENDED);
        uploadAll(session.id());
        SessionActor actor = mock(SessionActor.class);
        when(registry.find(session.id())).thenReturn(Optional.of(actor));
        doThrow(new PersistenceFlushException(session.id(), "flush failed"))
                .when(actor).forceFlush();

        assertThatThrownBy(() -> service.submit(student(), session.id()))
                .isInstanceOf(PersistenceFlushException.class);
        assertThat(submissions.size()).isZero();
        assertThat(sessions.findById(session.id()).orElseThrow().status())
                .isEqualTo(SessionStatus.ENDED);
        assertThat(assignments.find(taskId, STUDENT).orElseThrow().status())
                .isEqualTo(AssignmentStatus.ASSIGNED);
        assertThat(events.after(session.id(), 0, 100)).isEmpty();
        assertThat(publisher.events()).isEmpty();
        assertThat(lifecycle.cleanups()).isEmpty();
    }

    @Test
    void anotherStudentCannotSubmit() {
        TaskId taskId = createPublishedTask();
        Session session = newSession(taskId, SessionStatus.ENDED);
        uploadAll(session.id());

        assertCode(() -> service.submit(student2(), session.id()), ErrorCode.FORBIDDEN);
        assertThat(submissions.size()).isZero();
    }

    private SubmissionView submitAndCleanup(CurrentUser user, SessionId sessionId) {
        SubmissionView view = service.submit(user, sessionId);
        service.deleteOtherAttempts(user.accountId(), new TaskId(view.taskId()), sessionId);
        return view;
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

    private Session newSession(TaskId taskId, SessionStatus status) {
        Session session = Session.createPreparing(
                new SessionId(UUID.randomUUID()), SessionType.TASK, taskId, COURSE,
                STUDENT, TaskMode.FREE, 1, NOW);
        switch (status) {
            case PREPARING -> {
            }
            case IN_PROGRESS -> session.start(NOW);
            case ENDED -> {
                session.start(NOW);
                session.end(END);
            }
            case REVIEWING -> {
                session.start(NOW);
                session.end(END);
                session.markReviewing(END);
            }
            case SUBMITTED -> {
                session.start(NOW);
                session.end(END);
                session.submit(END);
            }
            case CANCELLED -> session.cancel(NOW);
            case FAILED -> {
                session.start(NOW);
                session.fail(FailureReason.INTERNAL, END);
            }
        }
        sessions.save(session);
        return sessions.findById(session.id()).orElseThrow();
    }

    private void uploadAll(SessionId sessionId) {
        upload(sessionId, StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO);
    }

    private void upload(SessionId sessionId, StreamType... types) {
        Recording recording = recordings.findBySession(sessionId)
                .orElseGet(() -> Recording.create(UUID.randomUUID(), sessionId, NOW));
        for (StreamType type : types) {
            recording.startStream(UUID.randomUUID(), TaskMode.FREE, type,
                    "upload-" + type.wireValue(), "recordings/test/"
                            + sessionId.value() + "/" + type.wireValue() + ".webm",
                    RecordingStream.DEFAULT_PART_SIZE, 0L, mime(type), NOW);
        }
        for (StreamType type : types) {
            recording.markStreamUploaded(type, TaskMode.FREE, 1, 1_024L, 5_000L,
                    mime(type), NOW);
        }
        recordings.save(recording);
    }

    private static String mime(StreamType type) {
        return type == StreamType.AUDIO ? "audio/webm" : "video/webm";
    }

    private static RecordingStream uploadedStream(
            UUID recordingId, StreamType type, long bytesUploaded, Long durationMs) {
        return new RecordingStream(
                UUID.randomUUID(), recordingId, type,
                "upload-" + type.wireValue(),
                "recordings/test/" + recordingId + "/" + type.wireValue() + ".webm",
                RecordingStream.DEFAULT_PART_SIZE, 1, bytesUploaded, durationMs, 0L,
                mime(type), StreamStatus.UPLOADED);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expected));
    }

    private record CleanupCall(AccountId student, TaskId task, SessionId keep) {
    }

    private static final class RecordingLifecycleRecorder implements RecordingLifecyclePort {
        private final List<CleanupCall> cleanups = new ArrayList<>();

        @Override
        public void abortAll(SessionId sessionId) {
        }

        @Override
        public void deleteAttempt(SessionId sessionId) {
        }

        @Override
        public void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep) {
            cleanups.add(new CleanupCall(student, task, keep));
        }

        @Override
        public boolean deleteExpiredTemporary(SessionId sessionId) {
            return false;
        }

        @Override
        public boolean deleteSubmittedSession(SessionId sessionId) {
            return false;
        }

        List<CleanupCall> cleanups() {
            return List.copyOf(cleanups);
        }
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
