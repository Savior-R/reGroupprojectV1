package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.CLASS;
import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.COURSE_ID;
import static com.shijiangtai.m3classroom.TestFixtures.NOW;
import static com.shijiangtai.m3classroom.TestFixtures.SCHOOL_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER_ID;
import static com.shijiangtai.m3classroom.TestFixtures.admin;
import static com.shijiangtai.m3classroom.TestFixtures.course;
import static com.shijiangtai.m3classroom.TestFixtures.coursePort;
import static com.shijiangtai.m3classroom.TestFixtures.freeDraft;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.student2;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.ContentRequest;
import com.shijiangtai.m3classroom.application.port.ContentResponse;
import com.shijiangtai.m3classroom.application.port.RecordingLifecyclePort;
import com.shijiangtai.m3classroom.application.view.SessionView;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.m3classroom.support.FakeScheduler;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.m3classroom.support.InMemorySessionTextStore;
import com.shijiangtai.m3classroom.support.InMemoryTaskAssignmentRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionApplicationServiceTest {

    private InMemoryTaskRepository tasks;
    private InMemoryTaskAssignmentRepository assignments;
    private FakeCourseQueryPort courses;
    private InMemorySessionRepository sessions;
    private InMemorySessionEventStore events;
    private InMemorySessionTextStore texts;
    private FakeScheduler scheduler;
    private RecordingLifecycleRecorder recording;
    private SessionRuntimeRegistry registry;
    private SessionApplicationService service;
    private TaskId taskId;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        assignments = new InMemoryTaskAssignmentRepository();
        courses = coursePort();
        sessions = new InMemorySessionRepository();
        events = new InMemorySessionEventStore();
        texts = new InMemorySessionTextStore();
        scheduler = new FakeScheduler();
        recording = new RecordingLifecycleRecorder();

        ContentClient unavailableContent = new ContentClient() {
            @Override
            public ContentResponse question(ContentRequest request) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "内容服务不可用");
            }

            @Override
            public ContentResponse answer(ContentRequest request) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "内容服务不可用");
            }
        };
        registry = new SessionRuntimeRegistry(
                sessions, tasks, events, texts, UUID::randomUUID, () -> NOW, scheduler,
                unavailableContent);
        service = new SessionApplicationServiceImpl(
                tasks, assignments, courses, new TaskAccessPolicy(courses),
                new SessionAccessPolicy(courses), sessions, events, registry, recording,
                UUID::randomUUID, () -> NOW);
        taskId = createPublishedTask();
    }

    @Test
    void assignedStudentCreatesPreparingSession() {
        SessionView view = service.createSession(student(), taskId);

        assertThat(view.sessionId()).isNotNull();
        assertThat(view.taskId()).isEqualTo(taskId.value());
        assertThat(view.courseId()).isEqualTo(COURSE_ID);
        assertThat(view.sessionType()).isEqualTo("task");
        assertThat(view.mode()).isEqualTo("free");
        assertThat(view.status()).isEqualTo("preparing");
        assertThat(view.attemptNo()).isEqualTo(1);
        assertThat(view.startedAt()).isNull();
        assertThat(view.endedAt()).isNull();
        assertThat(view.durationMs()).isNull();
        assertThat(view.streamOffsets()).isEmpty();
        assertThat(view.lastSeq()).isZero();
    }

    @Test
    void unassignedStudentIsForbidden() {
        assertCode(() -> service.createSession(student2(), taskId), ErrorCode.FORBIDDEN);
    }

    @Test
    void teacherCannotCreateSession() {
        assertCode(() -> service.createSession(teacher(), taskId), ErrorCode.FORBIDDEN);
        assertCode(() -> service.createSession(admin(), taskId), ErrorCode.FORBIDDEN);
    }

    @Test
    void closedTaskRejectsNewSession() {
        Task task = tasks.findById(taskId).orElseThrow();
        task.close(NOW);
        tasks.save(task);

        assertCode(() -> service.createSession(student(), taskId), ErrorCode.TASK_CLOSED);
    }

    @Test
    void draftTaskIsNotVisibleToStudents() {
        Task draft = Task.createDraft(
                new TaskId(UUID.randomUUID()), COURSE, TEACHER, freeDraft(), NOW);
        tasks.save(draft);

        assertCode(() -> service.createSession(student(), draft.id()),
                ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void archivedOrEndedCourseRejectsNewSession() {
        courses.addCourse(new CourseInfo(COURSE_ID, SCHOOL_ID, "语文教学论", false,
                Instant.parse("2027-06-30T00:00:00Z"), List.of(TEACHER_ID)));
        assertCode(() -> service.createSession(student(), taskId), ErrorCode.COURSE_ARCHIVED);

        courses.addCourse(new CourseInfo(COURSE_ID, SCHOOL_ID, "语文教学论", true,
                NOW.minusSeconds(1), List.of(TEACHER_ID)));
        assertCode(() -> service.createSession(student(), taskId), ErrorCode.COURSE_ENDED);
    }

    @Test
    void activeSessionBlocksSecondCreate() {
        service.createSession(student(), taskId);

        assertCode(() -> service.createSession(student(), taskId),
                ErrorCode.SESSION_ACTIVE_EXISTS);
    }

    @Test
    void secondAttemptGetsAttemptNoTwo() {
        SessionView first = service.createSession(student(), taskId);
        service.cancel(student(), new SessionId(first.sessionId()));

        SessionView second = service.createSession(student(), taskId);

        assertThat(second.attemptNo()).isEqualTo(2);
        assertThat(second.status()).isEqualTo("preparing");
    }

    @Test
    void startRequiresRecordingReady() {
        SessionView created = service.createSession(student(), taskId);

        assertCode(() -> service.start(student(), new SessionId(created.sessionId())),
                ErrorCode.SESSION_STATE_INVALID);
        assertThat(service.get(student(), new SessionId(created.sessionId())).status())
                .isEqualTo("preparing");
    }

    @Test
    void startEndAndCancelFollowStateMachine() {
        SessionView created = service.createSession(student(), taskId);
        SessionId sessionId = new SessionId(created.sessionId());
        SessionActor actor = registry.getOrCreate(sessionId);
        assertThat(actor.handle(ready("ready-1")).ok()).isTrue();

        SessionView started = service.start(student(), sessionId);
        assertThat(started.status()).isEqualTo("in_progress");
        assertThat(started.startedAt()).isNotNull();
        assertThat(started.lastSeq()).isEqualTo(2);

        SessionView ended = service.end(student(), sessionId);
        assertThat(ended.status()).isEqualTo("ended");
        assertThat(ended.endedAt()).isNotNull();
        assertThat(ended.durationMs()).isNotNull();

        SessionView second = service.createSession(student(), taskId);
        assertThat(second.attemptNo()).isEqualTo(2);
        service.cancel(student(), new SessionId(second.sessionId()));

        assertThat(service.get(student(), new SessionId(second.sessionId())).status())
                .isEqualTo("cancelled");
        assertThat(recording.aborted()).containsExactly(new SessionId(second.sessionId()));
        assertThat(registry.find(new SessionId(second.sessionId()))).isEmpty();
    }

    @Test
    void startAndEndRejectOtherStudents() {
        SessionView created = service.createSession(student(), taskId);
        SessionId sessionId = new SessionId(created.sessionId());

        assertCode(() -> service.start(student2(), sessionId), ErrorCode.FORBIDDEN);
        assertCode(() -> service.end(student2(), sessionId), ErrorCode.FORBIDDEN);
        assertCode(() -> service.cancel(student2(), sessionId), ErrorCode.FORBIDDEN);
    }

    @Test
    void teacherCannotReadUnsubmittedSession() {
        SessionView created = service.createSession(student(), taskId);

        assertCode(() -> service.get(teacher(), new SessionId(created.sessionId())),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void teacherCanReadSubmittedSession() {
        SessionView created = service.createSession(student(), taskId);
        Session session = sessions.findById(new SessionId(created.sessionId())).orElseThrow();
        session.start(NOW);
        session.end(NOW);
        session.submit(NOW);
        sessions.save(session);

        SessionView view = service.get(teacher(), new SessionId(created.sessionId()));

        assertThat(view.status()).isEqualTo("submitted");
    }

    @Test
    void getUnknownSessionIsNotFound() {
        assertCode(() -> service.get(student(), new SessionId(UUID.randomUUID())),
                ErrorCode.RESOURCE_NOT_FOUND);
    }

    private TaskId createPublishedTask() {
        Task task = Task.createDraft(
                new TaskId(UUID.randomUUID()), COURSE, TEACHER, freeDraft(), NOW);
        TaskSnapshot snapshot = new TaskSnapshotFactory().create(
                task, course(), List.of(), NOW);
        task.publish(snapshot, NOW);
        tasks.save(task);
        assignments.saveAll(List.of(new TaskAssignment(
                UUID.randomUUID(), task.id(), STUDENT, CLASS, NOW, AssignmentStatus.ASSIGNED)));
        return task.id();
    }

    private static SessionCommand.RecordingReady ready(String commandId) {
        List<SessionCommand.RecordingReady.StreamStatus> streams = new ArrayList<>();
        for (StreamType type : StreamType.values()) {
            streams.add(new SessionCommand.RecordingReady.StreamStatus(type, "ready"));
        }
        return new SessionCommand.RecordingReady(commandId, streams);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expected));
    }

    private static final class RecordingLifecycleRecorder implements RecordingLifecyclePort {
        private final List<SessionId> aborted = new ArrayList<>();

        @Override
        public void abortAll(SessionId sessionId) {
            aborted.add(sessionId);
        }

        @Override
        public void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep) {
            // no-op
        }

        List<SessionId> aborted() {
            return List.copyOf(aborted);
        }
    }
}
