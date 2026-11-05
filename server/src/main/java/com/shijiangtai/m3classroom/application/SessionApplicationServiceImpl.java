package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.application.port.RecordingLifecyclePort;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.view.SessionView;
import com.shijiangtai.m3classroom.application.view.SubmissionView;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.CommandResult;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session lifecycle application service (02 §4.3).
 *
 * <p>Creation validates the published task, the student assignment, the course window and the
 * single-active-session rule, then stores a {@code preparing} attempt. Start/end/rest-cancel go
 * through the live {@link SessionActor}, so REST is only an idempotent fallback for the
 * WebSocket control channel.
 */
@Service
public class SessionApplicationServiceImpl implements SessionApplicationService {

    private static final SessionType TASK_SESSION = SessionType.TASK;
    /** 05B §6: at most five temporary (non-submitted) attempts per student and task. */
    private static final int MAX_TEMPORARY_ATTEMPTS = 5;

    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository assignmentRepository;
    private final CourseQueryPort courseQueryPort;
    private final TaskAccessPolicy taskAccessPolicy;
    private final SessionAccessPolicy sessionAccessPolicy;
    private final SessionRepository sessionRepository;
    private final SessionEventStore eventStore;
    private final SubmissionService submissionService;
    private final SessionRuntimeRegistry runtimeRegistry;
    private final RecordingLifecyclePort recordingLifecyclePort;
    private final SessionStartGuard startGuard;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;

    public SessionApplicationServiceImpl(
            TaskRepository taskRepository,
            TaskAssignmentRepository assignmentRepository,
            CourseQueryPort courseQueryPort,
            TaskAccessPolicy taskAccessPolicy,
            SessionAccessPolicy sessionAccessPolicy,
            SessionRepository sessionRepository,
            SessionEventStore eventStore,
            SubmissionService submissionService,
            SessionRuntimeRegistry runtimeRegistry,
            RecordingLifecyclePort recordingLifecyclePort,
            SessionStartGuard startGuard,
            IdGenerator idGenerator,
            ClockProvider clockProvider) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.assignmentRepository = Objects.requireNonNull(
                assignmentRepository, "assignmentRepository");
        this.courseQueryPort = Objects.requireNonNull(courseQueryPort, "courseQueryPort");
        this.taskAccessPolicy = Objects.requireNonNull(taskAccessPolicy, "taskAccessPolicy");
        this.sessionAccessPolicy = Objects.requireNonNull(
                sessionAccessPolicy, "sessionAccessPolicy");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.submissionService = Objects.requireNonNull(
                submissionService, "submissionService");
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.recordingLifecyclePort = Objects.requireNonNull(
                recordingLifecyclePort, "recordingLifecyclePort");
        this.startGuard = Objects.requireNonNull(startGuard, "startGuard");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clockProvider = Objects.requireNonNull(clockProvider, "clockProvider");
    }

    @Override
    @Transactional
    public SessionView createSession(CurrentUser student, TaskId taskId) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(taskId, "taskId");

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> notFound("任务不存在", "task_id", taskId.value()));
        requireCreatable(task, taskId);

        CourseInfo course = courseQueryPort.findCourse(task.courseId())
                .orElseThrow(() -> notFound("课程不存在", "course_id", task.courseId().value()));
        TaskAssignment assignment = assignmentRepository
                .find(taskId, student.accountId()).orElse(null);
        taskAccessPolicy.requireStudentAssigned(student, course, assignment);
        if (!course.active()) {
            throw new DomainException(ErrorCode.COURSE_ARCHIVED, "课程已归档",
                    Map.of("course_id", course.id().toString()));
        }
        if (course.endAt() != null && !course.endAt().isAfter(clockProvider.now())) {
            throw new DomainException(ErrorCode.COURSE_ENDED, "课程已结束",
                    Map.of("course_id", course.id().toString()));
        }

        try {
            // The task row lock serializes validation + insert for the same task, matching
            // the publish flow and avoiding a race on the partial active-session index.
            return taskRepository.withLock(taskId, lockedTask -> {
                requireCreatable(lockedTask, taskId);
                if (sessionRepository.findActiveByStudentAndTask(
                        student.accountId(), taskId).isPresent()) {
                    throw activeSessionExists(taskId);
                }
                deleteOldestTemporaryAttemptIfAtLimit(student.accountId(), taskId);
                int attemptNo = Math.toIntExact(
                        sessionRepository.countByStudentAndTask(
                                student.accountId(), taskId) + 1);
                Session session = Session.createPreparing(
                        new SessionId(idGenerator.nextId()),
                        TASK_SESSION,
                        taskId,
                        lockedTask.courseId(),
                        student.accountId(),
                        TaskMode.fromWire(lockedTask.snapshot().mode()),
                        attemptNo,
                        clockProvider.now());
                try {
                    sessionRepository.save(session);
                } catch (DataIntegrityViolationException exception) {
                    throw activeSessionExists(taskId);
                }
                return toView(session);
            });
        } catch (DataIntegrityViolationException exception) {
            // The unique active-session index may surface while flushing/committing.
            throw activeSessionExists(taskId);
        }
    }

    private static void requireCreatable(Task task, TaskId taskId) {
        if (task.status() == TaskStatus.DRAFT) {
            // Drafts are invisible to students.
            throw notFound("任务不存在", "task_id", taskId.value());
        }
        if (task.status() == TaskStatus.CLOSED) {
            throw new DomainException(ErrorCode.TASK_CLOSED, "任务已关闭",
                    Map.of("task_id", taskId.value().toString()));
        }
        if (task.snapshot() == null) {
            throw new DomainException(ErrorCode.TASK_CONFIG_INVALID, "任务快照不存在",
                    Map.of("task_id", taskId.value().toString()));
        }
    }

    /**
     * Enforces the temporary-attempt cap (05B §6). The list is ordered by attempt number, so the
     * first match in each priority group is the oldest attempt. Submitted attempts are never
     * candidates; an active attempt cannot reach this point because the active-session check runs
     * first.
     */
    private void deleteOldestTemporaryAttemptIfAtLimit(AccountId studentId, TaskId taskId) {
        List<Session> attempts = sessionRepository.findByStudentAndTask(studentId, taskId);
        long temporary = attempts.stream()
                .filter(attempt -> attempt.status() != SessionStatus.SUBMITTED)
                .count();
        if (temporary < MAX_TEMPORARY_ATTEMPTS) {
            return;
        }
        Session oldest = oldestDeletable(
                attempts, SessionStatus.CANCELLED, SessionStatus.FAILED);
        if (oldest == null) {
            oldest = oldestDeletable(
                    attempts, SessionStatus.ENDED, SessionStatus.REVIEWING);
        }
        if (oldest == null) {
            throw activeSessionExists(taskId);
        }
        recordingLifecyclePort.deleteAttempt(oldest.id());
    }

    private static Session oldestDeletable(
            List<Session> attempts, SessionStatus first, SessionStatus second) {
        return attempts.stream()
                .filter(attempt -> attempt.status() == first || attempt.status() == second)
                .min(Comparator.comparingInt(Session::attemptNo))
                .orElse(null);
    }

    private static DomainException activeSessionExists(TaskId taskId) {
        return new DomainException(ErrorCode.SESSION_ACTIVE_EXISTS, "已有进行中的会话",
                Map.of("task_id", taskId.value().toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public SessionView get(CurrentUser user, SessionId sessionId) {
        Session session = findSession(sessionId);
        sessionAccessPolicy.requireRead(user, session);
        return toView(session);
    }

    @Override
    @Transactional
    public SessionView start(CurrentUser student, SessionId sessionId) {
        Session session = findSession(sessionId);
        sessionAccessPolicy.requireOwner(student, session);
        if (session.status() == SessionStatus.IN_PROGRESS) {
            return toView(session);
        }
        // Re-check the task/course after the preparing wait: an archived or ended course must
        // not start a session even though creation succeeded earlier (05A §4.3).
        startGuard.requireStartable(session);
        SessionActor actor = runtimeRegistry.getOrCreate(sessionId);
        requireOk(actor.handle(new SessionCommand.StartSession(
                idGenerator.nextId().toString())));
        Session updated = actor.session();
        sessionRepository.save(updated);
        return toView(updated);
    }

    /**
     * Ending may legitimately fail with {@link ErrorCode#SESSION_FAILED} after the actor moved to
     * {@code FAILED}, so every {@link DomainException} thrown by {@code end} must commit the
     * transaction it already performed writes in. Those pre-throw writes are the registry's
     * terminal session row (same transaction) and the terminal batch flush ({@code REQUIRES_NEW});
     * rolling back would leave the derived {@code failed} row uncommitted while the live actor has
     * already been evicted. Infrastructure failures that are not domain exceptions keep the
     * default rollback behavior.
     */
    @Override
    @Transactional(noRollbackFor = DomainException.class)
    public SessionView end(CurrentUser student, SessionId sessionId) {
        Session session = findSession(sessionId);
        sessionAccessPolicy.requireOwner(student, session);
        if (isFinished(session.status())) {
            return toView(session);
        }
        SessionActor actor = runtimeRegistry.getOrCreate(sessionId);
        String commandId = idGenerator.nextId().toString();
        SessionCommand command = session.mode() == TaskMode.INTERVIEW
                ? new SessionCommand.EndInterview(commandId)
                : new SessionCommand.EndSession(commandId);
        requireOk(actor.handle(command));
        Session updated = actor.session();
        sessionRepository.save(updated);
        runtimeRegistry.remove(sessionId);
        return toView(updated);
    }

    @Override
    @Transactional
    public void cancel(CurrentUser student, SessionId sessionId) {
        Session session = findSession(sessionId);
        sessionAccessPolicy.requireOwner(student, session);
        SessionActor actor = runtimeRegistry.find(sessionId).orElse(null);
        Session live;
        if (actor == null) {
            session.cancel(clockProvider.now());
            live = session;
        } else {
            // Cancel through the live aggregate so its timers stop and later callbacks
            // observe a terminal status; persist that same instance.
            actor.cancel(clockProvider.now());
            live = actor.session();
        }
        sessionRepository.save(live);
        runtimeRegistry.remove(sessionId);
        recordingLifecyclePort.abortAll(sessionId);
    }

    /**
     * Deliberately not transactional: the submit must commit before superseded attempts are
     * removed. Registering the cleanup from inside {@code submit}'s transaction would run it in
     * the commit callback while the transaction is still reported as active, so the deletes would
     * join a completed transaction and never commit.
     */
    @Override
    public SubmissionView submit(CurrentUser student, SessionId sessionId) {
        SubmissionView submission = submissionService.submit(student, sessionId);
        submissionService.deleteOtherAttempts(
                student.accountId(), new TaskId(submission.taskId()), sessionId);
        return submission;
    }

    private Session findSession(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> notFound("会话不存在", "session_id",
                        sessionId.value()));
    }

    private static boolean isFinished(SessionStatus status) {
        return status == SessionStatus.ENDED
                || status == SessionStatus.REVIEWING
                || status == SessionStatus.SUBMITTED;
    }

    private static void requireOk(CommandResult result) {
        if (!result.ok()) {
            throw new DomainException(result.errorCode(),
                    result.message() == null ? "命令处理失败" : result.message());
        }
    }

    private SessionView toView(Session session) {
        Map<String, Long> offsets = new LinkedHashMap<>();
        for (Map.Entry<StreamType, Long> entry : session.streamOffsets().entrySet()) {
            offsets.put(entry.getKey().wireValue(), entry.getValue());
        }
        // Asynchronous persistence may lag the live actor; report its in-memory seq so REST
        // clients never observe a rewound last_seq.
        long lastSeq = runtimeRegistry.find(session.id())
                .map(SessionActor::lastSeq)
                .orElseGet(() -> eventStore.maxSeq(session.id()));
        return new SessionView(
                session.id().value(),
                session.taskId().value(),
                session.courseId() == null ? null : session.courseId().value(),
                session.type().wireValue(),
                session.mode().wireValue(),
                session.status().wireValue(),
                session.attemptNo(),
                session.startedAt() == null ? null : session.startedAt().toString(),
                session.endedAt() == null ? null : session.endedAt().toString(),
                session.durationMs(),
                offsets,
                lastSeq);
    }

    private static DomainException notFound(String message, String field, Object value) {
        return new DomainException(ErrorCode.RESOURCE_NOT_FOUND, message,
                Map.of(field, value.toString()));
    }
}
