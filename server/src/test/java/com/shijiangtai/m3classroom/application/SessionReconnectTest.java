package com.shijiangtai.m3classroom.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.domain.classroom.ClassroomStateMachine;
import com.shijiangtai.m3classroom.domain.classroom.VirtualStudentDynamics;
import com.shijiangtai.m3classroom.domain.interview.InterviewStateMachine;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.CommandResult;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.session.RecordingState;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionContext;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.RubricWeights;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.m3classroom.support.FakeContentClient;
import com.shijiangtai.m3classroom.support.FakeScheduler;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.m3classroom.support.InMemorySessionTextStore;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Tick, 30 s grace, resume and failure semantics (05A §6.2/§8, implementation/04 §4.5).
 *
 * <p>The actor is registered in a real {@link SessionRuntimeRegistry} backed by the in-memory
 * repositories, so the tests also verify that the registry starts the 1 s tick and stops it (and
 * removes the actor) on terminal states.
 */
class SessionReconnectTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000300");
    private static final UUID COURSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID SCHOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STUDENT_ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID VIRTUAL_STUDENT_1 =
            UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID VIRTUAL_STUDENT_2 =
            UUID.fromString("00000000-0000-0000-0000-000000000402");

    @Test
    void tickOutsideInProgressIsNoOp() {
        ReconnectFixture fixture = fixture();

        fixture.actor().onTick(NOW.plusSeconds(1));

        assertThat(fixture.context().after(0)).isEmpty();
        assertThat(fixture.eventStore().size()).isZero();

        start(fixture);
        fixture.actor().handle(new SessionCommand.EndSession("end-1"));
        long seqAfterEnd = fixture.context().lastSeq();

        fixture.actor().onTick(NOW.plusSeconds(60));

        assertThat(fixture.context().lastSeq()).isEqualTo(seqAfterEnd);
        assertThat(eventsOfType(fixture, "session.time_tick")).isEmpty();
    }

    @Test
    void tickEmitsTimeTickAndUpdatesDynamics() {
        ReconnectFixture fixture = fixture();
        start(fixture);
        List<SessionEvent> pushed = new CopyOnWriteArrayList<>();
        fixture.actor().attachEventSink(pushed::add);
        double attentionBefore = fixture.dynamics().find(VIRTUAL_STUDENT_1).attention();

        fixture.actor().onTick(NOW.plusMillis(1_000));

        assertThat(fixture.dynamics().find(VIRTUAL_STUDENT_1).attention())
                .isLessThan(attentionBefore);
        List<SessionEvent> ticks = eventsOfType(fixture, "session.time_tick");
        assertThat(ticks).hasSize(1);
        SessionEvent tick = ticks.get(0);
        assertThat(tick.payload()).containsKeys(
                "elapsed_ms", "remaining_ms", "discipline_level", "states");
        assertThat(tick.payload().get("elapsed_ms")).isEqualTo(1_000L);
        assertThat(tick.payload().get("remaining_ms")).isEqualTo(1_199_000L);
        assertThat(tick.payload().get("discipline_level")).isEqualTo(0);
        assertThat(tick.payload().get("states")).isInstanceOf(List.class);
        List<?> states = (List<?>) tick.payload().get("states");
        assertThat(states).hasSize(2);
        for (Object state : states) {
            assertThat(state).isInstanceOf(Map.class);
            Map<?, ?> stateMap = (Map<?, ?>) state;
            assertThat(stateMap.containsKey("virtual_student_id")).isTrue();
            assertThat(stateMap.containsKey("state")).isTrue();
            assertThat(stateMap.containsKey("attention")).isTrue();
            assertThat(stateMap.containsKey("hand_raise_energy")).isTrue();
        }

        assertThat(pushed).contains(tick);
        assertThat(fixture.eventStore().all()).contains(tick);
    }

    @Test
    void disconnectWithinGraceDoesNotFailAndResumeCancelsGrace() {
        ReconnectFixture fixture = fixture();
        start(fixture);

        fixture.actor().onWsDisconnected(NOW);
        fixture.scheduler().advance(Duration.ofSeconds(29));

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(eventsOfType(fixture, "session.failed")).isEmpty();
        assertThat(fixture.registry().find(fixture.session().id())).isPresent();

        CommandResult resumed = fixture.actor().resume(
                fixture.context().lastSeq(), RecordingState.ACTIVE);
        assertThat(resumed.ok()).isTrue();

        fixture.scheduler().advance(Duration.ofSeconds(10));

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(eventsOfType(fixture, "session.failed")).isEmpty();
    }

    @Test
    void reconnectAndStartCancelsPreparingGrace() {
        ReconnectFixture fixture = fixture();
        fixture.actor().onWsDisconnected(NOW);
        fixture.scheduler().advance(Duration.ofSeconds(10));
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.PREPARING);

        fixture.actor().onWsConnected();
        fixture.scheduler().advance(Duration.ofSeconds(31));
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.PREPARING);
        assertThat(eventsOfType(fixture, "session.failed")).isEmpty();

        assertThat(fixture.actor().handle(ready("ready-1")).ok()).isTrue();
        assertThat(fixture.actor()
                .handle(new SessionCommand.StartSession("start-1")).ok()).isTrue();
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);

        fixture.scheduler().advance(Duration.ofSeconds(30));

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(eventsOfType(fixture, "session.failed")).isEmpty();
    }

    @Test
    void startCancelsPreparingGraceWithoutReconnect() {
        ReconnectFixture fixture = fixture();
        assertThat(fixture.actor().handle(ready("ready-1")).ok()).isTrue();
        fixture.actor().onWsDisconnected(NOW);
        fixture.scheduler().advance(Duration.ofSeconds(10));

        assertThat(fixture.actor()
                .handle(new SessionCommand.StartSession("start-1")).ok()).isTrue();
        fixture.scheduler().advance(Duration.ofSeconds(30));

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(eventsOfType(fixture, "session.failed")).isEmpty();
    }

    @Test
    void terminalActorIsEvictedOnlyWhenPersistedRowIsTerminal() {
        ToggleSessionRepository sessions = new ToggleSessionRepository();
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        tasks.save(publishedTask());
        ReconnectFixture fixture = fixture(
                new InMemorySessionEventStore(), sessions, tasks);
        start(fixture);
        sessions.save(fixture.actor().session());
        sessions.failSaves();

        fixture.actor().fail(FailureReason.GRACE_TIMEOUT);

        assertThat(fixture.registry().find(fixture.session().id())).contains(fixture.actor());
        assertThat(fixture.registry.removeIfPersistedTerminal(fixture.session().id())).isFalse();
        assertThat(fixture.registry().find(fixture.session().id())).contains(fixture.actor());

        sessions.allowSaves();
        sessions.save(fixture.actor().session());

        assertThat(fixture.registry.removeIfPersistedTerminal(fixture.session().id())).isTrue();
        assertThat(fixture.registry().find(fixture.session().id())).isEmpty();
    }

    @Test
    void preparingDisconnectFailsAfterGraceWithoutStart() {
        ReconnectFixture fixture = fixture();

        fixture.actor().onWsDisconnected(NOW);
        fixture.scheduler().advance(Duration.ofSeconds(29));

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.PREPARING);
        assertThat(eventsOfType(fixture, "session.failed")).isEmpty();
        assertThat(fixture.registry().find(fixture.session().id())).isPresent();

        fixture.scheduler().advance(Duration.ofSeconds(1));

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.FAILED);
        List<SessionEvent> failed = eventsOfType(fixture, "session.failed");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).payload().get("reason")).isEqualTo("grace_timeout");
        assertThat(fixture.eventStore().all()).contains(failed.get(0));
        assertThat(fixture.registry().find(fixture.session().id())).isEmpty();
        assertThat(fixture.sessions().findById(fixture.session().id()).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
        assertThat(fixture.scheduler().pendingTasks()).isZero();
    }

    @Test
    void graceTimeoutFailsSessionAndRemovesActor() {
        ReconnectFixture fixture = fixture();
        start(fixture);

        fixture.actor().onWsDisconnected(NOW);
        fixture.scheduler().advance(Duration.ofSeconds(29));

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(eventsOfType(fixture, "session.failed")).isEmpty();

        fixture.scheduler().advance(Duration.ofSeconds(1));

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.FAILED);
        List<SessionEvent> failed = eventsOfType(fixture, "session.failed");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).payload().get("reason")).isEqualTo("grace_timeout");
        assertThat(fixture.eventStore().all()).contains(failed.get(0));
        assertThat(fixture.registry().find(fixture.session().id())).isEmpty();
        assertThat(fixture.sessions().findById(fixture.session().id()).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
        assertThat(fixture.scheduler().pendingTasks()).isZero();
    }

    @Test
    void resumeActiveReplaysEventsAfterLastSeqWithoutTimeTick() {
        ReconnectFixture fixture = fixture();
        start(fixture);
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        long seenThrough = 2L;
        fixture.actor().onTick(NOW.plusMillis(1_000));
        fixture.actor().handle(new SessionCommand.EndQa("end-qa-1"));
        long lastSeqBeforeResume = fixture.context().lastSeq();
        assertThat(lastSeqBeforeResume).isGreaterThan(seenThrough + 1);
        assertThat(eventsOfType(fixture, "session.time_tick")).hasSize(1);

        CommandResult result = fixture.actor().resume(seenThrough, RecordingState.ACTIVE);

        assertThat(result.ok()).isTrue();
        assertThat(result.events()).isNotEmpty();
        SessionEvent resumed = result.events().get(0);
        assertThat(resumed.type()).isEqualTo("session.resumed");
        assertThat(resumed.seq()).isEqualTo(lastSeqBeforeResume + 1);
        assertThat(resumed.payload().get("last_seq")).isEqualTo(seenThrough);
        assertThat(resumed.payload().get("server_time_ms"))
                .isEqualTo(NOW.toEpochMilli());
        assertThat(result.events()).noneMatch(event -> event.seq() <= seenThrough);
        assertThat(result.events()).noneMatch(
                event -> event.type().equals("session.time_tick"));
        assertThat(result.events().subList(1, result.events().size()))
                .isSortedAccordingTo(
                        (left, right) -> Long.compare(left.seq(), right.seq()));
        assertThat(result.events()).anyMatch(
                event -> event.type().equals("classroom.question_asked") && event.seq() == 3L);
    }

    @Test
    void resumeLostFailsImmediately() {
        ReconnectFixture fixture = fixture();
        start(fixture);

        CommandResult result = fixture.actor().resume(0L, RecordingState.LOST);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ErrorCode.SESSION_FAILED);
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.FAILED);
        List<SessionEvent> failed = eventsOfType(fixture, "session.failed");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).payload().get("reason")).isEqualTo("recording_lost");
        assertThat(fixture.registry().find(fixture.session().id())).isEmpty();
        assertThat(fixture.sessions().findById(fixture.session().id()).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void resumeAfterFailureReturnsSessionFailed() {
        ReconnectFixture fixture = fixture();
        start(fixture);
        fixture.actor().fail(FailureReason.INTERNAL);
        long seqAfterFailure = fixture.context().lastSeq();

        CommandResult result = fixture.actor().resume(0L, RecordingState.ACTIVE);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ErrorCode.SESSION_FAILED);
        assertThat(result.events()).isEmpty();
        assertThat(fixture.context().lastSeq()).isEqualTo(seqAfterFailure);
        assertThat(eventsOfType(fixture, "session.failed")).hasSize(1);
    }

    @Test
    void rebuiltActorSeedsLastSeqFromPersistedEventStore() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        tasks.save(publishedTask());
        ReconnectFixture fixture = fixture(
                new InMemorySessionEventStore(), new InMemorySessionRepository(), tasks);
        start(fixture);
        assertThat(fixture.actor().handle(new SessionCommand.AskClass("ask-1")).ok())
                .isTrue();
        long persistedMax = fixture.eventStore().maxSeq(fixture.session().id());
        fixture.sessions().save(fixture.actor().session());
        fixture.registry().remove(fixture.session().id());

        SessionActor rebuilt = fixture.registry().getOrCreate(fixture.session().id());

        assertThat(rebuilt).isNotSameAs(fixture.actor());
        assertThat(rebuilt.snapshot().lastSeq()).isEqualTo(persistedMax);
        CommandResult result = rebuilt.handle(new SessionCommand.AskClass("ask-2"));
        assertThat(result.ok()).isTrue();
        assertThat(result.events()).isNotEmpty();
        assertThat(result.events().get(0).seq()).isEqualTo(persistedMax + 1);
        assertThat(fixture.eventStore().maxSeq(fixture.session().id()))
                .isEqualTo(persistedMax + 2);
    }

    @Test
    void internalCommandFailureReturnsGenericMessage() {
        FailingEventStore eventStore = new FailingEventStore();
        ReconnectFixture fixture = fixture(
                eventStore, new InMemorySessionRepository(), new InMemoryTaskRepository());
        start(fixture);
        eventStore.failFromNow();

        CommandResult result = fixture.actor().handle(new SessionCommand.AskClass("ask-fail"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(result.message()).isEqualTo("命令处理失败");
    }

    @Test
    void registrySchedulesTickAndStopsOnTerminalState() {
        ReconnectFixture fixture = fixture();
        start(fixture);

        assertThat(fixture.scheduler().pendingTasks()).isEqualTo(1);
        fixture.scheduler().advance(Duration.ofSeconds(1));
        assertThat(eventsOfType(fixture, "session.time_tick")).hasSize(1);

        fixture.actor().handle(new SessionCommand.EndSession("end-1"));

        assertThat(fixture.scheduler().pendingTasks()).isZero();
        fixture.scheduler().advance(Duration.ofSeconds(10));
        assertThat(eventsOfType(fixture, "session.time_tick")).hasSize(1);
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.ENDED);
        assertThat(fixture.registry().find(fixture.session().id())).isEmpty();
        assertThat(fixture.sessions().findById(fixture.session().id()).orElseThrow().status())
                .isEqualTo(SessionStatus.ENDED);
    }

    @Test
    void normalCommandPushesEventsExactlyOnce() {
        ReconnectFixture fixture = fixture();
        start(fixture);
        List<SessionEvent> pushed = new CopyOnWriteArrayList<>();
        fixture.actor().attachEventSink(pushed::add);

        CommandResult result = fixture.actor().handle(new SessionCommand.AskClass("ask-1"));

        assertThat(result.events()).isNotEmpty();
        assertThat(pushed).containsExactlyElementsOf(result.events());
        assertThat(pushed.stream().map(SessionEvent::seq).distinct().count())
                .isEqualTo(pushed.size());
    }

    @Test
    void resumePushesResumedAndReplayTailExactlyOnceInOrder() {
        ReconnectFixture fixture = fixture();
        start(fixture);
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.actor().onTick(NOW.plusMillis(1_000));
        fixture.actor().handle(new SessionCommand.EndQa("end-qa-1"));
        List<SessionEvent> pushed = new CopyOnWriteArrayList<>();
        fixture.actor().attachEventSink(pushed::add);

        CommandResult result = fixture.actor().resume(2L, RecordingState.ACTIVE);

        assertThat(pushed).containsExactlyElementsOf(result.events());
        assertThat(pushed.get(0).type()).isEqualTo("session.resumed");
        assertThat(pushed).noneMatch(event -> event.type().equals("session.time_tick"));
        assertThat(pushed.subList(1, pushed.size()))
                .isSortedAccordingTo(
                        (left, right) -> Long.compare(left.seq(), right.seq()));
        assertThat(pushed.stream().map(SessionEvent::seq).distinct().count())
                .isEqualTo(pushed.size());
    }

    @Test
    void freeSessionAutoEndsAtMaxDurationWithTimeoutReason() {
        ReconnectFixture fixture = fixture();
        start(fixture);

        fixture.actor().onTick(NOW.plusSeconds(1_199));

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
        List<SessionEvent> ticks = eventsOfType(fixture, "session.time_tick");
        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).payload().get("remaining_ms")).isEqualTo(1_000L);

        fixture.actor().onTick(NOW.plusSeconds(1_200));

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.ENDED);
        List<SessionEvent> ended = eventsOfType(fixture, "session.ended");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).payload().get("reason")).isEqualTo("timeout");
        assertThat(eventsOfType(fixture, "session.time_tick")).hasSize(1);
        assertThat(fixture.scheduler().pendingTasks()).isZero();
        assertThat(fixture.registry().find(fixture.session().id())).isEmpty();
    }

    @Test
    void terminalSaveFailureKeepsActorAndPreventsInProgressResurrection() {
        ToggleSessionRepository sessions = new ToggleSessionRepository();
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        tasks.save(publishedTask());
        ReconnectFixture fixture = fixture(new InMemorySessionEventStore(), sessions, tasks);
        start(fixture);
        // Mirrors SessionApplicationServiceImpl.start: the live in_progress row is persisted.
        sessions.save(fixture.actor().session());
        sessions.failSaves();

        fixture.actor().fail(FailureReason.GRACE_TIMEOUT);

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.FAILED);
        assertThat(fixture.registry().find(fixture.session().id())).contains(fixture.actor());
        assertThat(fixture.registry().getOrCreate(fixture.session().id()).session().status())
                .isEqualTo(SessionStatus.FAILED);
        // The row write failed, so the stale row is still in_progress: the retained live actor
        // must shield in-process reconnects from rebuilding it.
        assertThat(sessions.findById(fixture.session().id()).orElseThrow().status())
                .isEqualTo(SessionStatus.IN_PROGRESS);
    }

    @Test
    void failCleanupRunsWhenSessionFailedPersistThrows() {
        FailingEventStore eventStore = new FailingEventStore();
        ReconnectFixture fixture = fixture(
                eventStore, new InMemorySessionRepository(), new InMemoryTaskRepository());
        start(fixture);
        fixture.actor().onWsDisconnected(NOW);
        eventStore.failFromNow();

        assertThatThrownBy(() -> fixture.actor().fail(FailureReason.INTERNAL))
                .isInstanceOf(IllegalStateException.class);

        assertThat(fixture.session().status()).isEqualTo(SessionStatus.FAILED);
        assertThat(fixture.scheduler().pendingTasks()).isZero();
        assertThat(fixture.registry().find(fixture.session().id())).isEmpty();
        assertThat(fixture.sessions().findById(fixture.session().id()).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
        assertThat(fixture.eventStore().all())
                .noneMatch(event -> event.type().equals("session.failed"));
        // The in-memory context appended the event before the store rejected it.
        assertThat(eventsOfType(fixture, "session.failed")).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------

    private ReconnectFixture fixture() {
        return fixture(new InMemorySessionEventStore(), new InMemorySessionRepository(),
                new InMemoryTaskRepository());
    }

    private ReconnectFixture fixture(
            InMemorySessionEventStore eventStore,
            SessionRepository sessions,
            TaskRepository tasks) {
        Session session = Session.createPreparing(
                new SessionId(UUID.randomUUID()), SessionType.TASK, new TaskId(TASK_ID),
                new CourseId(COURSE_ID), new AccountId(STUDENT_ACCOUNT_ID), TaskMode.FREE,
                1, NOW);
        TaskSnapshot taskSnapshot = freeSnapshot();
        IdGenerator ids = UUID::randomUUID;
        SessionContext context = new SessionContext(session.id(), ids::nextId, () -> NOW);
        VirtualStudentDynamics dynamics = new VirtualStudentDynamics(new Random(7));
        dynamics.initialize(taskSnapshot.virtualStudents().stream()
                .map(SessionReconnectTest::toConfig)
                .toList());
        FakeScheduler scheduler = new FakeScheduler();
        InMemorySessionTextStore textStore = new InMemorySessionTextStore();
        SessionActor actor = new SessionActor(
                session, taskSnapshot, context, new ClassroomStateMachine(), dynamics,
                new InterviewStateMachine(ids::nextId),
                new ContentGenerationService(new FakeContentClient()),
                eventStore, textStore, ids, () -> NOW, scheduler, new Random(11));
        sessions.save(session);
        SessionRuntimeRegistry registry = new SessionRuntimeRegistry(
                sessions, tasks, eventStore, textStore, ids, () -> NOW,
                scheduler, new FakeContentClient());
        registry.register(actor);
        return new ReconnectFixture(
                actor, session, context, scheduler, eventStore, dynamics, registry, sessions);
    }

    private static void start(ReconnectFixture fixture) {
        assertThat(fixture.actor().handle(ready("ready-1")).ok()).isTrue();
        assertThat(fixture.actor()
                .handle(new SessionCommand.StartSession("start-1")).ok()).isTrue();
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
    }

    private static SessionCommand.RecordingReady ready(String commandId) {
        List<SessionCommand.RecordingReady.StreamStatus> streams = new ArrayList<>();
        for (StreamType type : StreamType.values()) {
            streams.add(new SessionCommand.RecordingReady.StreamStatus(type, "ready"));
        }
        return new SessionCommand.RecordingReady(commandId, streams);
    }

    private static List<SessionEvent> eventsOfType(ReconnectFixture fixture, String type) {
        return fixture.context().after(0).stream()
                .filter(event -> event.type().equals(type))
                .toList();
    }

    private static TaskSnapshot freeSnapshot() {
        List<TaskSnapshot.VirtualStudentSnapshot> students = List.of(
                virtualStudent(VIRTUAL_STUDENT_1, "林小雨", 1),
                virtualStudent(VIRTUAL_STUDENT_2, "陈晨", 2));
        return new TaskSnapshot(
                1, TASK_ID, COURSE_ID, SCHOOL_ID, TaskMode.FREE.wireValue(), "荷塘月色",
                "部编版", new TaskSnapshot.DurationSnapshot(900, 1200),
                Map.of("content", 40, "expression", 30, "interaction", 30),
                new TaskSnapshot.MaterialSnapshot(
                        UUID.fromString("00000000-0000-0000-0000-000000000501"),
                        "material.pdf", "materials/secret", "application/pdf", 1024,
                        "REFERENCE_TEXT", 14),
                new TaskSnapshot.ScenarioSnapshot("班主任", "学生上课玩手机"),
                students, null, NOW, STUDENT_ACCOUNT_ID);
    }

    private static TaskSnapshot.VirtualStudentSnapshot virtualStudent(
            UUID id, String name, int seatNo) {
        return new TaskSnapshot.VirtualStudentSnapshot(
                id, name, "avatar-0" + seatNo, "PERSONALITY",
                new BigDecimal("0.60"), new BigDecimal("0.50"), new BigDecimal("0.70"),
                new BigDecimal("0.50"), new BigDecimal("0.40"), seatNo, seatNo);
    }

    private static VirtualStudentConfig toConfig(TaskSnapshot.VirtualStudentSnapshot student) {
        return new VirtualStudentConfig(
                student.virtualStudentId(), student.name(), student.avatar(),
                student.personalityText(), student.activity(), student.questionRate(),
                student.ability(), student.attentionBaseline(), student.disciplineProbability(),
                student.seatNo(), student.sortOrder());
    }

    private static Task publishedTask() {
        TaskDraft draft = new TaskDraft(TaskMode.FREE, "荷塘月色", "部编版", 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null);
        Task task = Task.createDraft(new TaskId(TASK_ID), new CourseId(COURSE_ID),
                new AccountId(UUID.randomUUID()), draft, NOW);
        TaskSnapshot snapshot = new TaskSnapshotFactory().create(
                task, new CourseInfo(COURSE_ID, SCHOOL_ID, "语文教学论", true,
                        Instant.parse("2027-06-30T00:00:00Z"), List.of()),
                List.of(), NOW);
        task.publish(snapshot, NOW);
        return task;
    }

    /** Event store that starts failing on demand, after the session has started. */
    private static final class FailingEventStore extends InMemorySessionEventStore {

        private boolean failing;

        private void failFromNow() {
            failing = true;
        }

        @Override
        public synchronized void append(SessionEvent event) {
            if (failing) {
                throw new IllegalStateException("event store unavailable");
            }
            super.append(event);
        }
    }

    /** Session repository whose terminal write can be made to fail. */
    private static final class ToggleSessionRepository implements SessionRepository {

        private final InMemorySessionRepository delegate = new InMemorySessionRepository();
        private boolean failSaves;

        private void failSaves() {
            failSaves = true;
        }

        private void allowSaves() {
            failSaves = false;
        }

        @Override
        public Session save(Session session) {
            if (failSaves) {
                throw new IllegalStateException("session row unavailable");
            }
            return delegate.save(session);
        }

        @Override
        public Optional<Session> findById(SessionId id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Session> findActiveByStudentAndTask(
                AccountId studentAccountId, TaskId taskId) {
            return delegate.findActiveByStudentAndTask(studentAccountId, taskId);
        }

        @Override
        public List<Session> findByStudentAndTask(
                AccountId studentAccountId, TaskId taskId) {
            return delegate.findByStudentAndTask(studentAccountId, taskId);
        }

        @Override
        public List<Session> findByStatusIn(java.util.Collection<SessionStatus> statuses) {
            return delegate.findByStatusIn(statuses);
        }

        @Override
        public long countByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
            return delegate.countByStudentAndTask(studentAccountId, taskId);
        }
    }

    private record ReconnectFixture(
            SessionActor actor,
            Session session,
            SessionContext context,
            FakeScheduler scheduler,
            InMemorySessionEventStore eventStore,
            VirtualStudentDynamics dynamics,
            SessionRuntimeRegistry registry,
            SessionRepository sessions) {
    }
}
