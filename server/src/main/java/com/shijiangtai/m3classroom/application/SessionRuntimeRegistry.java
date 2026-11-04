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
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
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
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(SessionRuntimeRegistry.class);
    private static final Duration TICK_INTERVAL = Duration.ofSeconds(1);

    private final Map<SessionId, SessionActor> actors = new ConcurrentHashMap<>();
    private final Map<SessionId, TickTask> ticks = new ConcurrentHashMap<>();
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
        SessionId id = actor.session().id();
        SessionActor previous = actors.put(id, actor);
        if (previous != null && previous != actor) {
            stopTick(id);
        }
        actor.attachStatusListener(status -> onStatusChanged(id, actor, status));
        ensureTick(id, actor);
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
            ensureTick(id, existing);
            return existing;
        }
        SessionActor actor = actors.computeIfAbsent(id, this::buildActor);
        ensureTick(id, actor);
        return actor;
    }

    public void remove(SessionId id) {
        Objects.requireNonNull(id, "id");
        actors.remove(id);
        stopTick(id);
    }

    // ------------------------------------------------------------------
    // Tick scheduling
    // ------------------------------------------------------------------

    /**
     * Starts the 1 s tick for an actor that is {@code in_progress}. The map's bin lock serializes
     * this with {@link #stopTick(SessionId)}; the actor monitor is deliberately checked outside
     * the mapping function to avoid a lock-order inversion with terminal transitions, which hold
     * the actor monitor and then touch this map.
     */
    private void ensureTick(SessionId id, SessionActor actor) {
        if (!actor.isInProgress()) {
            return;
        }
        ticks.compute(id, (key, existing) -> {
            if (existing != null) {
                return existing;
            }
            TickTask task = new TickTask(key, actor);
            task.scheduleNext();
            return task;
        });
        if (!actor.isInProgress()) {
            // The session went terminal while the tick was being installed; the status listener
            // may already have run before the insertion, so remove the now-stale task here.
            stopTick(id);
        }
    }

    private void stopTick(SessionId id) {
        TickTask task = ticks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Terminal transitions triggered by timers (grace timeout / recording lost) never pass
     * through the REST service, so the registry persists the terminal row itself. The row must be
     * durable <em>before</em> the actor is evicted: otherwise a concurrent reconnect would
     * reload the stale {@code in_progress} row and rebuild a live actor for a failed session.
     * When the save fails the live actor is deliberately kept registered (it already holds the
     * terminal state) and the failure is logged; as long as that actor lives, an in-process
     * reconnect observes its {@code failed}/{@code ended} status instead of reloading the stale
     * row.
     */
    private void onStatusChanged(SessionId id, SessionActor actor, SessionStatus status) {
        if (status == SessionStatus.IN_PROGRESS) {
            ensureTick(id, actor);
            return;
        }
        stopTick(id);
        try {
            sessionRepository.save(actor.session());
        } catch (RuntimeException exception) {
            LOG.error("Failed to persist terminal session {} ({}); keeping the live actor "
                    + "registered so it cannot be rebuilt as in_progress",
                    id.value(), status.wireValue(), exception);
            return;
        }
        actors.remove(id, actor);
    }

    /** One self-rescheduling 1 s tick; {@link #cancel()} stops both the current and next run. */
    private final class TickTask implements SchedulerPort.Cancellable {

        private final SessionId sessionId;
        private final SessionActor actor;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile SchedulerPort.Cancellable pending;

        private TickTask(SessionId sessionId, SessionActor actor) {
            this.sessionId = sessionId;
            this.actor = actor;
        }

        private void scheduleNext() {
            if (cancelled.get()) {
                return;
            }
            pending = scheduler.schedule(this::fire, TICK_INTERVAL);
        }

        private void fire() {
            if (cancelled.get()) {
                return;
            }
            try {
                actor.onTick(clockProvider.now());
            } finally {
                if (!cancelled.get() && actor.isInProgress()) {
                    scheduleNext();
                } else {
                    ticks.remove(sessionId, this);
                }
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            SchedulerPort.Cancellable current = pending;
            if (current != null) {
                current.cancel();
            }
        }
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

        VirtualStudentDynamics students = new VirtualStudentDynamics(new Random());
        if (session.mode() != TaskMode.INTERVIEW) {
            students.initialize(snapshot.virtualStudents().stream()
                    .map(SessionRuntimeRegistry::toConfig)
                    .toList());
        }
        SessionContext context = new SessionContext(
                id, idGenerator::nextId, clockProvider::now);
        SessionActor actor = new SessionActor(
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
                new Random());
        actor.attachStatusListener(status -> onStatusChanged(id, actor, status));
        return actor;
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
