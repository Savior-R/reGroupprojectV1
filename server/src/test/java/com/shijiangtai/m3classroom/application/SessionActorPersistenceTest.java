package com.shijiangtai.m3classroom.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.port.PersistenceFlushException;
import com.shijiangtai.m3classroom.domain.classroom.ClassroomStateMachine;
import com.shijiangtai.m3classroom.domain.classroom.VirtualStudentDynamics;
import com.shijiangtai.m3classroom.domain.interview.InterviewStateMachine;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.CommandResult;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionContext;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.m3classroom.support.BufferingContextPersistenceWriter;
import com.shijiangtai.m3classroom.support.FakeContentClient;
import com.shijiangtai.m3classroom.support.FakeScheduler;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionTextStore;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Actor write-path tests with a buffering writer: the in-memory {@link SessionContext} text
 * stream must feed content requests even while the writer has not flushed anything.
 */
class SessionActorPersistenceTest {

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
    private static final UUID MATERIAL_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final String TEACHER_FINAL = "请解释一下这一段。";

    @Test
    void teacherFinalStillReachesNextContentRequestBeforeFlush() {
        Fixture fixture = fixture();
        start(fixture);

        fixture.actor().recordSpeechFinal("teacher", null, TEACHER_FINAL, null, null, null);
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));

        SessionEvent raise = eventsOfType(fixture, "student.hand_raised").get(0);
        UUID studentId = (UUID) raise.payload().get("student_id");
        UUID handRaiseId = (UUID) raise.payload().get("hand_raise_id");
        CommandResult result = fixture.actor().handle(
                new SessionCommand.CallOn("call-1", studentId, handRaiseId));

        assertThat(result.ok()).isTrue();
        // The writer still holds the teacher final; the content request reads the actor's
        // in-memory text stream instead of the (empty) durable store.
        assertThat(fixture.writer().flushCalls()).isZero();
        assertThat(fixture.textStore().bySession(fixture.session().id(), "teacher")).isEmpty();
        assertThat(fixture.client().answerRequests()).hasSize(1);
        assertThat(fixture.client().answerRequests().get(0).recentTexts())
                .contains(TEACHER_FINAL);
    }

    @Test
    void nonTerminalCommandsDoNotForceFlush() {
        Fixture fixture = fixture();
        start(fixture);

        fixture.actor().recordSpeechFinal("teacher", null, TEACHER_FINAL, null, null, null);
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));

        assertThat(fixture.writer().flushCalls()).isZero();
        assertThat(fixture.writer().pendingTexts()).hasSize(1);
        assertThat(fixture.writer().pendingEvents()).isNotEmpty();
        assertThat(fixture.textStore().size()).isZero();
        assertThat(fixture.eventStore().size()).isZero();
    }

    @Test
    void forceFlushDrainsBufferedWrites() {
        Fixture fixture = fixture();
        start(fixture);
        fixture.actor().recordSpeechFinal("teacher", null, TEACHER_FINAL, null, null, null);
        long lastSeq = fixture.actor().lastSeq();

        fixture.actor().forceFlush();

        assertThat(fixture.writer().flushCalls()).isEqualTo(1);
        assertThat(fixture.writer().pendingEvents()).isEmpty();
        assertThat(fixture.writer().pendingTexts()).isEmpty();
        assertThat(fixture.textStore().size()).isEqualTo(1);
        assertThat(fixture.eventStore().size()).isEqualTo(Math.toIntExact(lastSeq));
        assertThat(fixture.eventStore().all())
                .extracting(SessionEvent::type)
                .contains("session.started");
    }

    @Test
    void endSessionFlushesBeforePushingEndedAndReturnsOk() {
        Fixture fixture = fixture();
        start(fixture);
        List<SessionEvent> pushed = new ArrayList<>();
        fixture.actor().attachEventSink(pushed::add);

        CommandResult result = fixture.actor().handle(
                new SessionCommand.EndSession("end-1"));

        assertThat(result.ok()).isTrue();
        assertThat(fixture.writer().flushCalls()).isGreaterThanOrEqualTo(1);
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.ENDED);
        assertThat(fixture.eventStore().all()).anyMatch(e -> e.type().equals("session.ended"));
        assertThat(pushed).anyMatch(e -> e.type().equals("session.ended"));
    }

    @Test
    void endSessionFlushFailureMarksFailedAndDoesNotPushEnded() {
        Fixture fixture = fixture();
        start(fixture);
        List<SessionEvent> pushed = new ArrayList<>();
        fixture.actor().attachEventSink(pushed::add);
        fixture.writer().failFlush = true;

        CommandResult result = fixture.actor().handle(
                new SessionCommand.EndSession("end-1"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ErrorCode.SESSION_FAILED);
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.FAILED);
        assertThat(eventsOfType(fixture, "session.failed")).hasSize(1);
        assertThat(pushed).noneMatch(e -> e.type().equals("session.ended"));
    }

    @Test
    void failFlushFailureStillCancelsTimersAndNotifiesRegistry() {
        Fixture fixture = fixture();
        start(fixture);
        List<SessionStatus> notified = new ArrayList<>();
        fixture.actor().attachStatusListener(notified::add);
        // A pending grace timer makes the cancelAllTasks assertion meaningful; without the
        // finally-block cleanup a throw from forceFlush would skip it.
        fixture.actor().onWsDisconnected(NOW);
        assertThat(fixture.scheduler().pendingTasks()).isEqualTo(1);
        fixture.writer().failFlush = true;

        assertThatThrownBy(() -> fixture.actor().fail(FailureReason.INTERNAL))
                .isInstanceOf(PersistenceFlushException.class);
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.FAILED);
        assertThat(fixture.scheduler().pendingTasks()).isZero();
        assertThat(notified).contains(SessionStatus.FAILED);
    }

    // ---------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------

    private static void start(Fixture fixture) {
        assertThat(fixture.actor().handle(ready("ready-1")).ok()).isTrue();
        assertThat(fixture.actor().handle(new SessionCommand.StartSession("start-1")).ok())
                .isTrue();
    }

    private static Fixture fixture() {
        UUID sessionUuid = UUID.randomUUID();
        Session session = Session.createPreparing(
                new SessionId(sessionUuid), SessionType.TASK, new TaskId(TASK_ID),
                new CourseId(COURSE_ID), new AccountId(STUDENT_ACCOUNT_ID), TaskMode.FREE,
                1, NOW);
        TaskSnapshot taskSnapshot = taskSnapshot();
        VirtualStudentDynamics dynamics = new VirtualStudentDynamics(new java.util.Random(7));
        dynamics.initialize(taskSnapshot.virtualStudents().stream()
                .map(SessionActorPersistenceTest::toConfig)
                .toList());
        IdGenerator ids = () -> UUID.randomUUID();
        SessionContext context = new SessionContext(session.id(), ids::nextId, () -> NOW);
        FakeScheduler scheduler = new FakeScheduler();
        InMemorySessionEventStore eventStore = new InMemorySessionEventStore();
        InMemorySessionTextStore textStore = new InMemorySessionTextStore();
        BufferingContextPersistenceWriter writer =
                new BufferingContextPersistenceWriter(eventStore, textStore);
        FakeContentClient client = new FakeContentClient();
        SessionActor actor = new SessionActor(
                session, taskSnapshot, context, new ClassroomStateMachine(), dynamics,
                new InterviewStateMachine(ids::nextId), new ContentGenerationService(client),
                writer, ids, () -> NOW, scheduler, new java.util.Random(11));
        return new Fixture(actor, session, context, scheduler, client, eventStore, textStore,
                writer);
    }

    private static TaskSnapshot taskSnapshot() {
        List<TaskSnapshot.VirtualStudentSnapshot> students = List.of(
                virtualStudent(VIRTUAL_STUDENT_1, "林小雨", 1),
                virtualStudent(VIRTUAL_STUDENT_2, "陈晨", 2));
        return new TaskSnapshot(
                1, TASK_ID, COURSE_ID, SCHOOL_ID, TaskMode.FREE.wireValue(), "荷塘月色", "部编版",
                new TaskSnapshot.DurationSnapshot(900, 1200),
                Map.of("content", 40, "expression", 30, "interaction", 30),
                new TaskSnapshot.MaterialSnapshot(MATERIAL_ID, "material.pdf",
                        "materials/material", "application/pdf", 1024, "参考文本", 4),
                new TaskSnapshot.ScenarioSnapshot("班主任", "学生上课玩手机"),
                students, null, NOW, STUDENT_ACCOUNT_ID);
    }

    private static TaskSnapshot.VirtualStudentSnapshot virtualStudent(
            UUID id, String name, int seatNo) {
        return new TaskSnapshot.VirtualStudentSnapshot(
                id, name, "avatar-0" + seatNo, "personality",
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

    private static SessionCommand.RecordingReady ready(String commandId) {
        List<SessionCommand.RecordingReady.StreamStatus> streams = new ArrayList<>();
        for (StreamType type : StreamType.values()) {
            streams.add(new SessionCommand.RecordingReady.StreamStatus(type, "ready"));
        }
        return new SessionCommand.RecordingReady(commandId, streams);
    }

    private static List<SessionEvent> eventsOfType(Fixture fixture, String type) {
        return fixture.context().after(0).stream()
                .filter(event -> event.type().equals(type))
                .toList();
    }

    private record Fixture(
            SessionActor actor,
            Session session,
            SessionContext context,
            FakeScheduler scheduler,
            FakeContentClient client,
            InMemorySessionEventStore eventStore,
            InMemorySessionTextStore textStore,
            BufferingContextPersistenceWriter writer) {
    }
}
