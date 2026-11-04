package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.SchedulerPort;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.domain.classroom.ClassroomStateMachine;
import com.shijiangtai.m3classroom.domain.classroom.VirtualStudentDynamics;
import com.shijiangtai.m3classroom.domain.interview.InterviewStateMachine;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionContext;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of live {@link SessionActor}s, one per active session.
 *
 * <p>{@link #getOrCreate(SessionId)} reloads the session and its published task snapshot and
 * builds the actor from the runtime ports. Free/scenario sessions start with the virtual
 * students snapshotted at publish time; interview sessions leave the classroom dynamics empty
 * and drive {@link InterviewStateMachine} from the snapshot instead.
 */
@Component
public final class SessionRuntimeRegistry {

    private final Map<SessionId, SessionActor> actors = new ConcurrentHashMap<>();
    private final SessionRepository sessionRepository;
    private final TaskRepository taskRepository;
    private final SessionEventStore eventStore;
    private final SessionTextStore textStore;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;
    private final SchedulerPort scheduler;
    private final ContentGenerationService contentGeneration;

    public SessionRuntimeRegistry(
            SessionRepository sessionRepository,
            TaskRepository taskRepository,
            SessionEventStore eventStore,
            SessionTextStore textStore,
            IdGenerator idGenerator,
            ClockProvider clockProvider,
            SchedulerPort scheduler,
            ContentClient contentClient) {
        this.sessionRepository = Objects.requireNonNull(
                sessionRepository, "sessionRepository");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.textStore = Objects.requireNonNull(textStore, "textStore");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clockProvider = Objects.requireNonNull(clockProvider, "clockProvider");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.contentGeneration = new ContentGenerationService(
                Objects.requireNonNull(contentClient, "contentClient"));
    }

    public void register(SessionActor actor) {
        Objects.requireNonNull(actor, "actor");
        actors.put(actor.session().id(), actor);
    }

    public Optional<SessionActor> find(SessionId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(actors.get(id));
    }

    /** Loads the session, builds its actor if needed and keeps a single actor per session. */
    public SessionActor getOrCreate(SessionId id) {
        Objects.requireNonNull(id, "id");
        SessionActor existing = actors.get(id);
        if (existing != null) {
            return existing;
        }
        return actors.computeIfAbsent(id, this::buildActor);
    }

    public void remove(SessionId id) {
        Objects.requireNonNull(id, "id");
        actors.remove(id);
    }

    private SessionActor buildActor(SessionId id) {
        Session session = sessionRepository.findById(id)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在",
                        Map.of("session_id", id.value().toString())));
        Task task = taskRepository.findById(session.taskId())
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在",
                        Map.of("task_id", session.taskId().value().toString())));
        TaskSnapshot snapshot = task.snapshot();
        if (snapshot == null) {
            throw new DomainException(ErrorCode.SESSION_STATE_INVALID, "任务快照不存在",
                    Map.of("task_id", task.id().value().toString()));
        }

        VirtualStudentDynamics students = new VirtualStudentDynamics(
                RandomGenerator.getDefault());
        if (session.mode() != TaskMode.INTERVIEW) {
            students.initialize(snapshot.virtualStudents().stream()
                    .map(SessionRuntimeRegistry::toConfig)
                    .toList());
        }
        SessionContext context = new SessionContext(
                id, idGenerator::nextId, clockProvider::now);
        return new SessionActor(
                session,
                snapshot,
                context,
                new ClassroomStateMachine(),
                students,
                new InterviewStateMachine(idGenerator::nextId),
                contentGeneration,
                eventStore,
                textStore,
                idGenerator,
                clockProvider,
                scheduler,
                RandomGenerator.getDefault());
    }

    private static VirtualStudentConfig toConfig(TaskSnapshot.VirtualStudentSnapshot student) {
        return new VirtualStudentConfig(
                student.virtualStudentId(),
                student.name(),
                student.avatar(),
                student.personalityText(),
                student.activity(),
                student.questionRate(),
                student.ability(),
                student.attentionBaseline(),
                student.disciplineProbability(),
                student.seatNo(),
                student.sortOrder());
    }
}
