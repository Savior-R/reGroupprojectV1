package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.ContextPersistenceWriter;
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
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ContextPersistenceWriter persistence;
    private final Supplier<RandomGenerator> randomFactory;

    /**
     * Production wiring: async batch persistence and a fresh {@link Random} per rebuilt actor.
     * Explicitly annotated because the test-seam overloads below make this a multi-constructor
     * class.
     */
    @Autowired
    public SessionRuntimeRegistry(
            SessionRepository sessionRepository,
            TaskRepository taskRepository,
            SessionEventStore eventStore,
            SessionTextStore textStore,
            IdGenerator idGenerator,
            ClockProvider clockProvider,
            SchedulerPort scheduler,
            ContentClient contentClient,
            ContextPersistenceWriter persistence) {
        this(sessionRepository, taskRepository, eventStore, textStore, idGenerator,
                clockProvider, scheduler, contentClient, persistence, Random::new);
    }

    /**
     * Backwards-compatible wiring: synchronous persistence through the two stores.
     */
    public SessionRuntimeRegistry(
            SessionRepository sessionRepository,
            TaskRepository taskRepository,
            SessionEventStore eventStore,
            SessionTextStore textStore,
            IdGenerator idGenerator,
            ClockProvider clockProvider,
            SchedulerPort scheduler,
            ContentClient contentClient) {
        this(sessionRepository, taskRepository, eventStore, textStore, idGenerator,
                clockProvider, scheduler, contentClient,
                new DirectContextPersistenceWriter(eventStore, textStore));
    }

    /**
     * Test seam: the factory supplies the generator used for hand-raise delay, behaviour noise
     * and follow-up rolls. Integration tests pass a shared deterministic generator so a teacher
     * ASR final always advances the dialogue. Persistence stays synchronous.
     */
    public SessionRuntimeRegistry(
            SessionRepository sessionRepository,
            TaskRepository taskRepository,
            SessionEventStore eventStore,
            SessionTextStore textStore,
            IdGenerator idGenerator,
            ClockProvider clockProvider,
            SchedulerPort scheduler,
            ContentClient contentClient,
            Supplier<RandomGenerator> randomFactory) {
        this(sessionRepository, taskRepository, eventStore, textStore, idGenerator,
                clockProvider, scheduler, contentClient,
                new DirectContextPersistenceWriter(eventStore, textStore), randomFactory);
    }

    /** Full test seam: explicit persistence writer and random factory. */
    public SessionRuntimeRegistry(
            SessionRepository sessionRepository,
            TaskRepository taskRepository,
            SessionEventStore eventStore,
            SessionTextStore textStore,
            IdGenerator idGenerator,
            ClockProvider clockProvider,
            SchedulerPort scheduler,
            ContentClient contentClient,
            ContextPersistenceWriter persistence,
            Supplier<RandomGenerator> randomFactory) {
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
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.randomFactory = Objects.requireNonNull(randomFactory, "randomFactory");
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
        closeWriter(id);
    }

    /**
     * Evicts a terminal actor only when the persisted row is also terminal.
     *
     * <p>This preserves {@link #onStatusChanged}'s save-failure shield: when persisting a
     * terminal transition fails, the live terminal actor is deliberately kept so an in-process
     * reconnect cannot reload the stale {@code in_progress} row and rebuild a runnable actor.
     * The control-WebSocket handler uses this on terminal-session connects instead of the
     * unconditional {@link #remove(SessionId)}.
     *
     * @return {@code true} when the actor was removed
     */
    public boolean removeIfPersistedTerminal(SessionId id) {
        Objects.requireNonNull(id, "id");
        SessionActor actor = actors.get(id);
        if (actor == null || !isTerminal(actor.session().status())) {
            return false;
        }
        Session persisted = sessionRepository.findById(id).orElse(null);
        if (persisted == null || !isTerminal(persisted.status())) {
            return false;
        }
        if (actors.remove(id, actor)) {
            stopTick(id);
            closeWriter(id);
            return true;
        }
        return false;
    }

    private static boolean isTerminal(SessionStatus status) {
        return status == SessionStatus.ENDED
                || status == SessionStatus.REVIEWING
                || status == SessionStatus.SUBMITTED
                || status == SessionStatus.CANCELLED
                || status == SessionStatus.FAILED;
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
        closeWriter(id);
    }

    /**
     * Releases the session's persistence queue after the actor is gone. A failed final flush is
     * logged and swallowed: terminal state is already persisted (or deliberately kept live), and
     * a writer failure must never fail the status transition that triggered the eviction.
     */
    private void closeWriter(SessionId id) {
        try {
            persistence.close(id);
        } catch (RuntimeException exception) {
            LOG.warn("Failed to close persistence queue for session {}", id.value(), exception);
        }
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

        VirtualStudentDynamics students = new VirtualStudentDynamics(randomFactory.get());
        if (session.mode() != TaskMode.INTERVIEW) {
            students.initialize(snapshot.virtualStudents().stream()
                    .map(SessionRuntimeRegistry::toConfig)
                    .toList());
        }
        // A rebuilt actor must continue after the durable event log. The in-memory context does
        // not replay history, but starting at 0 would re-emit seq values that
        // ON CONFLICT (session_id, seq) DO NOTHING silently drops.
        SessionContext context = new SessionContext(
                id, idGenerator::nextId, clockProvider::now, eventStore.maxSeq(id));
        // The in-memory context does not replay texts, but content requests must still see the
        // teacher utterances persisted before a restart. Hydrate from the unified text stream.
        textStore.bySession(id, "teacher").forEach(context::addText);
        SessionActor actor = new SessionActor(
                session,
                snapshot,
                context,
                new ClassroomStateMachine(),
                students,
                new InterviewStateMachine(idGenerator::nextId),
                contentGeneration,
                persistence,
                idGenerator,
                clockProvider,
                scheduler,
                randomFactory.get());
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
