package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.AfterCommitPort;
import com.shijiangtai.m3classroom.application.port.RecordingLifecyclePort;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.view.SubmissionView;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.RequiredStreams;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.submission.SubmissionCreatedEvent;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.EventPublisher;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Submission transaction (05B §6.9).
 *
 * <p>The order is fixed: flush the live actor, validate the required streams, then write the
 * submission, the submitted session, the submitted assignment and the {@code submission.created}
 * event in one transaction. A flush or event-store failure rolls the whole transaction back, so no
 * submission is left behind. Recording cleanup of superseded attempts runs only after commit.
 */
@Service
public class SubmissionServiceImpl implements SubmissionService {

    private static final String SUBMISSION_CREATED = "submission.created";

    private final SessionRepository sessionRepository;
    private final SessionAccessPolicy accessPolicy;
    private final SessionRuntimeRegistry runtimeRegistry;
    private final RecordingRepository recordingRepository;
    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final SessionEventStore eventStore;
    private final EventPublisher eventPublisher;
    private final RecordingLifecyclePort recordingLifecyclePort;
    private final AfterCommitPort afterCommitPort;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;

    public SubmissionServiceImpl(
            SessionRepository sessionRepository,
            SessionAccessPolicy accessPolicy,
            SessionRuntimeRegistry runtimeRegistry,
            RecordingRepository recordingRepository,
            TaskRepository taskRepository,
            TaskAssignmentRepository assignmentRepository,
            SubmissionRepository submissionRepository,
            SessionEventStore eventStore,
            EventPublisher eventPublisher,
            RecordingLifecyclePort recordingLifecyclePort,
            AfterCommitPort afterCommitPort,
            IdGenerator idGenerator,
            ClockProvider clockProvider) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.recordingRepository = Objects.requireNonNull(
                recordingRepository, "recordingRepository");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.assignmentRepository = Objects.requireNonNull(
                assignmentRepository, "assignmentRepository");
        this.submissionRepository = Objects.requireNonNull(
                submissionRepository, "submissionRepository");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.recordingLifecyclePort = Objects.requireNonNull(
                recordingLifecyclePort, "recordingLifecyclePort");
        this.afterCommitPort = Objects.requireNonNull(afterCommitPort, "afterCommitPort");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clockProvider = Objects.requireNonNull(clockProvider, "clockProvider");
    }

    @Override
    @Transactional
    public SubmissionView submit(CurrentUser student, SessionId sessionId) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(sessionId, "sessionId");

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> notFound("会话不存在", "session_id", sessionId.value()));
        accessPolicy.requireOwner(student, session);
        requireSubmittable(session);

        // Everything the live actor buffered must be durable before the submission references it.
        runtimeRegistry.find(sessionId).ifPresent(SessionActor::forceFlush);

        List<StreamType> missing = missingStreams(sessionId, session);
        if (!missing.isEmpty()) {
            throw new DomainException(ErrorCode.RECORDING_INCOMPLETE, "录制不完整",
                    Map.of("missing_streams",
                            missing.stream().map(StreamType::wireValue).toList()));
        }

        Task task = taskRepository.findById(session.taskId())
                .orElseThrow(() -> notFound("任务不存在", "task_id", session.taskId().value()));
        TaskAssignment assignment = assignmentRepository
                .find(task.id(), student.accountId())
                .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, "任务未分配给当前学生"));
        if (submissionRepository
                .findByTaskAndStudent(task.id(), student.accountId()).isPresent()) {
            throw new DomainException(ErrorCode.SESSION_STATE_INVALID, "任务已提交",
                    Map.of("task_id", task.id().value().toString()));
        }

        Instant now = clockProvider.now();
        Submission submission = Submission.create(
                idGenerator.nextId(), task.id(), student.accountId(), sessionId,
                assignment.classIdSnapshot(), now);
        submissionRepository.save(submission);

        session.submit(now);
        sessionRepository.save(session);

        assignmentRepository.saveAll(List.of(new TaskAssignment(
                assignment.id(),
                assignment.taskId(),
                assignment.studentAccountId(),
                assignment.classIdSnapshot(),
                assignment.assignedAt(),
                AssignmentStatus.SUBMITTED)));

        appendSubmissionCreated(session, submission, now);
        eventPublisher.publish(new SubmissionCreatedEvent(
                submission.id(),
                task.id().value(),
                sessionId.value(),
                student.accountId().value(),
                assignment.classIdSnapshot() == null
                        ? null : assignment.classIdSnapshot().value(),
                now));

        afterCommitPort.afterCommit(() -> deleteOtherAttempts(
                student.accountId(), task.id(), sessionId));
        return toView(submission);
    }

    @Override
    public void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep) {
        recordingLifecyclePort.deleteOtherAttempts(student, task, keep);
    }

    private static void requireSubmittable(Session session) {
        if (session.status() != SessionStatus.ENDED
                && session.status() != SessionStatus.REVIEWING) {
            throw new DomainException(ErrorCode.SESSION_STATE_INVALID, "当前状态不允许提交",
                    Map.of("action", "submit", "status", session.status().wireValue()));
        }
    }

    private List<StreamType> missingStreams(SessionId sessionId, Session session) {
        Set<StreamType> uploaded = EnumSet.noneOf(StreamType.class);
        Recording recording = recordingRepository.findBySession(sessionId).orElse(null);
        if (recording != null) {
            for (RecordingStream stream : recording.streams()) {
                if (stream.status() == StreamStatus.UPLOADED) {
                    uploaded.add(stream.type());
                }
            }
        }
        return RequiredStreams.missing(uploaded, session.mode());
    }

    private void appendSubmissionCreated(Session session, Submission submission, Instant now) {
        long seq = eventStore.maxSeq(session.id()) + 1;
        long atMs = session.durationMs() == null ? 0L : session.durationMs();
        eventStore.append(new SessionEvent(
                idGenerator.nextId(),
                session.id(),
                seq,
                SUBMISSION_CREATED,
                atMs,
                Map.of("submission_id", submission.id().toString(),
                        "task_id", submission.taskId().value().toString(),
                        "session_id", session.id().value().toString()),
                now));
    }

    private static SubmissionView toView(Submission submission) {
        return new SubmissionView(
                submission.id(),
                submission.taskId().value(),
                submission.sessionId().value(),
                submission.studentAccountId().value(),
                submission.status(),
                submission.submittedAt().toString());
    }

    private static DomainException notFound(String message, String field, Object value) {
        return new DomainException(ErrorCode.RESOURCE_NOT_FOUND, message,
                Map.of(field, value.toString()));
    }
}
