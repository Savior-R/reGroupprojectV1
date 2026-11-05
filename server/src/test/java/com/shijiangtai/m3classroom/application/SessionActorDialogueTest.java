package com.shijiangtai.m3classroom.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.classroom.ClassroomStateMachine;
import com.shijiangtai.m3classroom.domain.classroom.QaPhase;
import com.shijiangtai.m3classroom.domain.classroom.VirtualStudentBehavior;
import com.shijiangtai.m3classroom.domain.classroom.VirtualStudentDynamics;
import com.shijiangtai.m3classroom.domain.interview.InterviewStateMachine;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionContext;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.m3classroom.support.FakeContentClient;
import com.shijiangtai.m3classroom.support.FakeScheduler;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionTextStore;
import com.shijiangtai.m3classroom.support.MutableRandom;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
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

/** Actor-level tests for the initial answer -> dialogue decision (M3B multi-turn dialogue). */
class SessionActorDialogueTest {

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

    @Test
    void winningFollowUpRollStartsDialogueAndEmitsFollowUp() {
        Fixture f = fixture(new MutableRandom(0.0)); // question_rate 0.5 -> p = 0.25, 0.0 wins
        f.start();
        f.actor().handle(f.callOn());

        List<SessionEvent> events = f.context().after(0);
        assertThat(events).extracting(SessionEvent::type).containsSubsequence(
                "teacher.called_on", "student.answer",
                "classroom.state_changed", "student.state_changed",
                "dialogue.started", "student.follow_up");
        SessionEvent started = events.stream()
                .filter(e -> e.type().equals("dialogue.started")).findFirst().orElseThrow();
        assertThat(started.payload().get("student_id")).isEqualTo(f.studentId());
        SessionEvent followUp = events.stream()
                .filter(e -> e.type().equals("student.follow_up")).findFirst().orElseThrow();
        assertThat(followUp.payload().get("answer_id")).isEqualTo(f.answerId(events));
        assertThat(followUp.payload().get("student_id")).isEqualTo(f.studentId());
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);
        assertThat(f.dynamics().find(f.studentId()).behavior())
                .isEqualTo(VirtualStudentBehavior.DIALOGUE);
        assertThat(f.textStore().all())
                .anySatisfy(text -> assertThat(text.kind()).isEqualTo("follow_up"));
        assertThat(f.scheduler().pendingTasks()).isZero();
    }

    @Test
    void losingFollowUpRollKeepsLegacyAnswerFinishedPath() {
        Fixture f = fixture(new MutableRandom(0.99));
        f.start();
        f.actor().handle(f.callOn());
        assertThat(f.context().after(0)).extracting(SessionEvent::type)
                .doesNotContain("dialogue.started", "student.follow_up");
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        assertThat(f.dynamics().find(f.studentId()).behavior())
                .isEqualTo(VirtualStudentBehavior.FOCUSED);
    }

    @Test
    void failingContentClientStillPersistsTemplateFollowUp() {
        FakeContentClient client = FakeContentClient.failing();
        Fixture f = fixture(new MutableRandom(0.0), client);
        f.start();
        f.actor().handle(f.callOn());

        SessionEvent followUp = f.lastEvent("student.follow_up");
        assertThat(followUp).isNotNull();
        SessionText text = f.textStore().all().stream()
                .filter(candidate -> "follow_up".equals(candidate.kind()))
                .findFirst()
                .orElseThrow();
        assertThat(text.text()).isEqualTo(ContentGenerationService.QUESTION_FALLBACK);
        assertThat(text.confidence()).isEqualByComparingTo("0.0");
        assertThat(text.speakerType()).isEqualTo("student");
        assertThat(text.speakerRef()).isEqualTo(f.studentId());
        assertThat(text.source()).isEqualTo("llm");
        assertThat(text.status()).isEqualTo("final");
        assertThat(followUp.payload().get("text_id")).isEqualTo(text.id());
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);
    }

    // ---------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------

    private static Fixture fixture(MutableRandom random) {
        return fixture(random, new FakeContentClient());
    }

    private static Fixture fixture(MutableRandom random, FakeContentClient client) {
        Session session = Session.createPreparing(
                new SessionId(UUID.randomUUID()), SessionType.TASK, new TaskId(TASK_ID),
                new CourseId(COURSE_ID), new AccountId(STUDENT_ACCOUNT_ID), TaskMode.FREE,
                1, NOW);
        TaskSnapshot taskSnapshot = taskSnapshot();
        VirtualStudentDynamics dynamics = new VirtualStudentDynamics(new java.util.Random(7));
        dynamics.initialize(taskSnapshot.virtualStudents().stream()
                .map(SessionActorDialogueTest::toConfig)
                .toList());
        IdGenerator ids = UUID::randomUUID;
        SessionContext context = new SessionContext(session.id(), ids::nextId, () -> NOW);
        ClassroomStateMachine classroom = new ClassroomStateMachine();
        InterviewStateMachine interview = new InterviewStateMachine(ids::nextId);
        FakeScheduler scheduler = new FakeScheduler();
        InMemorySessionEventStore eventStore = new InMemorySessionEventStore();
        InMemorySessionTextStore textStore = new InMemorySessionTextStore();
        SessionActor actor = new SessionActor(
                session, taskSnapshot, context, classroom, dynamics, interview,
                new ContentGenerationService(client), eventStore, textStore, ids,
                () -> NOW, scheduler, random);
        return new Fixture(actor, context, classroom, dynamics, textStore, scheduler);
    }

    private static TaskSnapshot taskSnapshot() {
        return new TaskSnapshot(
                1, TASK_ID, COURSE_ID, SCHOOL_ID, TaskMode.FREE.wireValue(), "荷塘月色", "部编版",
                new TaskSnapshot.DurationSnapshot(900, 1200),
                Map.of("content", 40, "expression", 30, "interaction", 30),
                new TaskSnapshot.MaterialSnapshot(MATERIAL_ID, "material.pdf",
                        "materials/material.pdf", "application/pdf", 1024, "reference", 9),
                new TaskSnapshot.ScenarioSnapshot("班主任", "学生上课玩手机"),
                List.of(virtualStudent(VIRTUAL_STUDENT_1, "林小雨", 1),
                        virtualStudent(VIRTUAL_STUDENT_2, "陈晨", 2)),
                null, NOW, STUDENT_ACCOUNT_ID);
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

    private static final class Fixture {

        private final SessionActor actor;
        private final SessionContext context;
        private final ClassroomStateMachine classroom;
        private final VirtualStudentDynamics dynamics;
        private final InMemorySessionTextStore textStore;
        private final FakeScheduler scheduler;
        private UUID studentId;

        private Fixture(
                SessionActor actor,
                SessionContext context,
                ClassroomStateMachine classroom,
                VirtualStudentDynamics dynamics,
                InMemorySessionTextStore textStore,
                FakeScheduler scheduler) {
            this.actor = actor;
            this.context = context;
            this.classroom = classroom;
            this.dynamics = dynamics;
            this.textStore = textStore;
            this.scheduler = scheduler;
        }

        private SessionActor actor() {
            return actor;
        }

        private SessionContext context() {
            return context;
        }

        private ClassroomStateMachine classroom() {
            return classroom;
        }

        private VirtualStudentDynamics dynamics() {
            return dynamics;
        }

        private InMemorySessionTextStore textStore() {
            return textStore;
        }

        private FakeScheduler scheduler() {
            return scheduler;
        }

        private UUID studentId() {
            return studentId;
        }

        private void start() {
            assertThat(actor.handle(ready("ready-1")).ok()).isTrue();
            assertThat(actor.handle(new SessionCommand.StartSession("start-1")).ok()).isTrue();
        }

        /**
         * Asks the class, lets the hand-raise timers fire and returns the call-on command for the
         * first raised hand. The command itself is executed by the test via {@code handle}.
         */
        private SessionCommand.CallOn callOn() {
            assertThat(actor.handle(new SessionCommand.AskClass("ask-1")).ok()).isTrue();
            scheduler.advance(Duration.ofSeconds(3));
            SessionEvent raise = lastEvent("student.hand_raised");
            studentId = (UUID) raise.payload().get("student_id");
            return new SessionCommand.CallOn(
                    "call-1", studentId, (UUID) raise.payload().get("hand_raise_id"));
        }

        /** The {@code answer_id} of the (single) {@code student.answer} event. */
        private UUID answerId(List<SessionEvent> events) {
            return events.stream()
                    .filter(event -> event.type().equals("student.answer"))
                    .map(event -> (UUID) event.payload().get("answer_id"))
                    .findFirst()
                    .orElseThrow();
        }

        private SessionEvent lastEvent(String type) {
            return context.after(0).stream()
                    .filter(event -> event.type().equals(type))
                    .reduce((first, second) -> second)
                    .orElseThrow();
        }
    }
}
