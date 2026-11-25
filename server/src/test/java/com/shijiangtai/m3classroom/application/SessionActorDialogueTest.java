package com.shijiangtai.m3classroom.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.classroom.ClassroomMacroState;
import com.shijiangtai.m3classroom.domain.classroom.ClassroomStateMachine;
import com.shijiangtai.m3classroom.domain.classroom.QaPhase;
import com.shijiangtai.m3classroom.domain.classroom.VirtualStudentBehavior;
import com.shijiangtai.m3classroom.domain.classroom.VirtualStudentDynamics;
import com.shijiangtai.m3classroom.domain.interview.InterviewStateMachine;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.CommandResult;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Actor-level tests for the first-version call-on / dialogue behaviour (05A §5.5). */
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
    void callOnStartsTeacherDialogueWithFirstAnswer() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        assertThat(f.actor().handle(f.callOn()).ok()).isTrue();

        List<SessionEvent> events = f.context().after(0);
        assertThat(events).extracting(SessionEvent::type).containsSubsequence(
                "teacher.called_on", "dialogue.started",
                "classroom.state_changed", "student.answer");
        SessionEvent started = f.lastEvent("dialogue.started");
        assertThat(started.payload().get("initiator")).isEqualTo("teacher");
        SessionEvent answer = f.lastEvent("student.answer");
        assertThat(answer.payload().get("turn")).isEqualTo(1);
        assertThat(answer.payload().get("dialogue_id"))
                .isEqualTo(started.payload().get("dialogue_id"));
        assertThat(answer.payload().get("answer_id")).isNotNull();
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);
        assertThat(f.dynamics().find(f.studentId()).behavior())
                .isEqualTo(VirtualStudentBehavior.DIALOGUE);
        assertThat(f.countEvents("student.follow_up")).isZero();
        assertThat(f.scheduler().pendingTasks()).isZero();
    }

    @Test
    void teacherFollowUpGeneratesNextStudentAnswer() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());

        f.actor().recordSpeechFinal("teacher", null, "对，你能再具体说说吗？",
                1000L, 2000L, BigDecimal.valueOf(0.9));

        List<SessionEvent> events = f.context().after(0);
        assertThat(events).extracting(SessionEvent::type).containsSubsequence(
                "teacher.intent_classified", "teacher.reply", "student.answer");
        SessionEvent reply = f.lastEvent("teacher.reply");
        assertThat(reply.payload().get("intent")).isEqualTo("ask_class");
        assertThat(reply.payload().get("turn")).isEqualTo(1);
        SessionEvent answer = f.lastEvent("student.answer");
        assertThat(answer.payload().get("turn")).isEqualTo(2);
        assertThat(f.countEvents("student.answer")).isEqualTo(2);
        assertThat(f.countEvents("student.follow_up")).isZero();
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);
    }

    @Test
    void commentOnlyWritesTeacherReplyAndDoesNotAdvance() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());
        int answersBefore = f.countEvents("student.answer");

        f.actor().recordSpeechFinal("teacher", null, "很好",
                1000L, 2000L, BigDecimal.valueOf(0.9));

        SessionEvent reply = f.lastEvent("teacher.reply");
        assertThat(reply.payload().get("intent")).isEqualTo("comment_answer");
        assertThat(reply.payload().get("turn")).isEqualTo(1);
        assertThat(f.countEvents("student.answer")).isEqualTo(answersBefore);
        assertThat(f.countEvents("teacher.marked")).isZero();
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);
    }

    @Test
    void sitDownEndsWholeQaAndReturnsToLecturing() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());

        f.actor().recordSpeechFinal("teacher", null, "好，请坐",
                1000L, 2000L, BigDecimal.valueOf(0.9));

        List<SessionEvent> events = f.context().after(0);
        assertThat(events).extracting(SessionEvent::type).containsSubsequence(
                "teacher.reply", "dialogue.ended", "student.state_changed",
                "classroom.qa_ended", "classroom.state_changed");
        SessionEvent ended = f.lastEvent("dialogue.ended");
        assertThat(ended.payload().get("reason")).isEqualTo("sit_down");
        assertThat(ended.payload().get("rounds")).isEqualTo(1);
        assertThat(f.lastEvent("classroom.qa_ended").payload().get("reason"))
                .isEqualTo("sit_down");

        SessionEvent transition = f.lastEvent("classroom.state_changed");
        assertThat(transition.payload().get("to")).isEqualTo("lecturing");
        assertThat(transition.payload().get("qa_phase")).isNull();
        assertThat(transition.payload().get("trigger")).isEqualTo("sit_down");
        assertThat(f.classroom().macroState()).isEqualTo(ClassroomMacroState.LECTURING);
        assertThat(f.classroom().qaPhase()).isNull();
        assertThat(f.dynamics().find(f.studentId()).behavior())
                .isEqualTo(VirtualStudentBehavior.FOCUSED);
        assertThat(f.context().after(ended.seq()).stream()
                .filter(event -> event.type().equals("classroom.state_changed"))
                .filter(event -> "waiting_hands".equals(event.payload().get("qa_phase")))
                .toList()).isEmpty();
    }

    @Test
    void studentInitiatedDialogueFollowsUpAndCanEndWithoutFollowUp() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        assertThat(f.actor().handle(f.callOnStudentQuestion()).ok()).isTrue();

        SessionEvent started = f.lastEvent("dialogue.started");
        assertThat(started.payload().get("initiator")).isEqualTo("student");
        SessionEvent question = f.lastEvent("student.question");
        assertThat(question.payload().get("turn")).isEqualTo(1);
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);
        assertThat(f.dynamics().find(f.studentId()).behavior())
                .isEqualTo(VirtualStudentBehavior.DIALOGUE);

        f.actor().recordSpeechFinal("teacher", null, "因为光合作用需要光能",
                1000L, 2000L, BigDecimal.valueOf(0.9));

        SessionEvent reply = f.lastEvent("teacher.reply");
        assertThat(reply.payload().get("intent")).isEqualTo("other");
        SessionEvent followUp = f.lastEvent("student.follow_up");
        assertThat(followUp.payload().get("turn")).isEqualTo(2);
        assertThat(followUp.payload().get("answer_id"))
                .isEqualTo(question.payload().get("question_id"));
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);

        f.random().set(0.99);
        f.actor().recordSpeechFinal("teacher", null, "嗯",
                3000L, 4000L, BigDecimal.valueOf(0.9));

        SessionEvent ended = f.lastEvent("dialogue.ended");
        assertThat(ended.payload().get("reason")).isEqualTo("no_follow_up");
        assertThat(ended.payload().get("rounds")).isEqualTo(2);
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        assertThat(f.dynamics().find(f.studentId()).behavior())
                .isEqualTo(VirtualStudentBehavior.FOCUSED);
    }

    @Test
    void studentInitiatedDialogueWithoutFollowUpReturnsToWaitingHands() {
        Fixture f = fixture(new MutableRandom(0.99));
        f.start();
        f.actor().handle(f.callOnStudentQuestion());

        f.actor().recordSpeechFinal("teacher", null, "因为光合作用需要光能",
                1000L, 2000L, BigDecimal.valueOf(0.9));

        SessionEvent ended = f.lastEvent("dialogue.ended");
        assertThat(ended.payload().get("reason")).isEqualTo("no_follow_up");
        assertThat(ended.payload().get("rounds")).isEqualTo(1);
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        assertThat(f.dynamics().find(f.studentId()).behavior())
                .isEqualTo(VirtualStudentBehavior.FOCUSED);
    }

    @Test
    void finalStartedBeforeBoundaryDoesNotAdvanceDialogue() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());

        f.advanceClock(Duration.ofSeconds(1));
        f.actor().recordSpeechFinal("teacher", null, "你能再说说吗？",
                1000L, 2000L, BigDecimal.valueOf(0.9));
        assertThat(f.countEvents("student.answer")).isEqualTo(2);

        f.actor().recordSpeechFinal("teacher", null, "上一句话",
                500L, 600L, BigDecimal.valueOf(0.9));
        assertThat(f.countEvents("student.answer")).isEqualTo(2);
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);
    }

    @Test
    void thirdRoundTeacherFollowUpEndsWithMaxRounds() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());

        f.actor().recordSpeechFinal("teacher", null, "你能再说说吗？",
                1000L, 2000L, BigDecimal.valueOf(0.9));
        f.actor().recordSpeechFinal("teacher", null, "你能再说说吗？",
                3000L, 4000L, BigDecimal.valueOf(0.9));
        f.actor().recordSpeechFinal("teacher", null, "你能再说说吗？",
                5000L, 6000L, BigDecimal.valueOf(0.9));

        SessionEvent ended = f.lastEvent("dialogue.ended");
        assertThat(ended.payload().get("reason")).isEqualTo("max_rounds");
        assertThat(ended.payload().get("rounds")).isEqualTo(3);
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        assertThat(f.countEvents("student.answer")).isEqualTo(3);
    }

    @Test
    void manualEndDialogueEndsCurrentDialogueOnly() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());
        UUID dialogueId = f.activeDialogueId();

        assertThat(f.actor().handle(new SessionCommand.EndDialogue(
                "end-1", dialogueId)).ok()).isTrue();

        assertThat(f.lastEvent("dialogue.ended").payload().get("reason"))
                .isEqualTo("teacher");
        assertThat(f.lastEvent("classroom.state_changed").payload().get("trigger"))
                .isEqualTo("end_dialogue");
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        assertThat(f.dynamics().find(f.studentId()).behavior())
                .isEqualTo(VirtualStudentBehavior.FOCUSED);
    }

    @Test
    void endQaWhileInDialogueEmitsDialogueEndedFirst() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());

        assertThat(f.actor().handle(new SessionCommand.EndQa("qa-1")).ok()).isTrue();

        assertThat(f.context().after(0)).extracting(SessionEvent::type)
                .containsSubsequence("dialogue.ended", "student.state_changed",
                        "classroom.qa_ended", "classroom.state_changed");
        assertThat(f.lastEvent("dialogue.ended").payload().get("reason"))
                .isEqualTo("qa_ended");
        assertThat(f.classroom().macroState()).isEqualTo(ClassroomMacroState.LECTURING);
    }

    @Test
    void askClassWhileInDialogueRestartsAfterDialogueEnded() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());

        assertThat(f.actor().handle(new SessionCommand.AskClass("ask-2")).ok()).isTrue();

        assertThat(f.context().after(0)).extracting(SessionEvent::type)
                .containsSubsequence("dialogue.ended", "student.state_changed",
                        "classroom.question_asked", "classroom.state_changed");
        assertThat(f.lastEvent("dialogue.ended").payload().get("reason"))
                .isEqualTo("qa_restarted");
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
    }

    @Test
    void endSessionWhileInDialogueEmitsDialogueEndedBeforeSessionEnded() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());

        assertThat(f.actor().handle(new SessionCommand.EndSession("end-3")).ok()).isTrue();

        assertThat(f.context().after(0)).extracting(SessionEvent::type)
                .containsSubsequence("dialogue.ended", "student.state_changed",
                        "session.ended");
        assertThat(f.lastEvent("dialogue.ended").payload().get("reason"))
                .isEqualTo("session_ended");
        assertThat(f.dynamics().find(f.studentId()).behavior())
                .isEqualTo(VirtualStudentBehavior.FOCUSED);
    }

    @Test
    void failWhileInDialogueEmitsDialogueEndedBeforeSessionFailed() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());

        f.actor().fail(FailureReason.GRACE_TIMEOUT);

        assertThat(f.context().after(0)).extracting(SessionEvent::type)
                .containsSubsequence("dialogue.ended", "student.state_changed",
                        "session.failed");
        assertThat(f.lastEvent("dialogue.ended").payload().get("reason"))
                .isEqualTo("session_ended");
        assertThat(f.dynamics().find(f.studentId()).behavior())
                .isEqualTo(VirtualStudentBehavior.FOCUSED);
    }

    @Test
    void classificationDuringDialogueDoesNotTriggerVoiceQuestion() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());
        int askedBefore = f.countEvents("classroom.question_asked");
        int suggestedBefore = f.countEvents("classroom.question_suggested");

        f.actor().onTeacherSpeechClassified(0.9);

        assertThat(f.countEvents("classroom.question_asked")).isEqualTo(askedBefore);
        assertThat(f.countEvents("classroom.question_suggested")).isEqualTo(suggestedBefore);
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);
    }

    @Test
    void lateVoiceClassificationAfterSitDownDoesNotOpenQa() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        f.actor().handle(f.callOn());
        f.actor().recordSpeechFinal("teacher", null, "好，请坐",
                1000L, 2000L, BigDecimal.valueOf(0.9));
        int askedBefore = f.countEvents("classroom.question_asked");

        f.actor().onTeacherSpeechClassified(0.9);

        assertThat(f.countEvents("classroom.question_asked")).isEqualTo(askedBefore);
        assertThat(f.classroom().macroState()).isEqualTo(ClassroomMacroState.LECTURING);
    }

    @Test
    void voiceCallOnInWaitingHandsStartsDialogue() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();
        assertThat(f.actor().handle(new SessionCommand.AskClass("ask-voice")).ok()).isTrue();

        f.actor().recordSpeechFinal("teacher", null, "林小雨你来说",
                1000L, 2000L, BigDecimal.valueOf(0.9));

        SessionEvent calledOn = f.lastEvent("teacher.called_on");
        assertThat(calledOn.payload().get("student_id")).isEqualTo(VIRTUAL_STUDENT_1);
        assertThat(f.lastEvent("dialogue.started").payload().get("initiator"))
                .isEqualTo("teacher");
        assertThat(f.classroom().qaPhase()).isEqualTo(QaPhase.DIALOGUE);
        assertThat(f.lastEvent("student.answer").payload().get("turn")).isEqualTo(1);
    }

    @Test
    void teacherIntentClassifiedIsEmittedForEveryClassroomFinal() {
        Fixture f = fixture(new MutableRandom(0.0));
        f.start();

        f.actor().recordSpeechFinal("teacher", null, "同学们看这里",
                0L, 1000L, BigDecimal.valueOf(0.9));

        SessionEvent classified = f.lastEvent("teacher.intent_classified");
        assertThat(classified.payload().get("primary_intent")).isEqualTo("other");
        assertThat(classified.payload().get("text_id")).isNotNull();
        assertThat(classified.payload().get("model_version"))
                .isEqualTo("teacher-intent-rules-v1");
        assertThat(f.countEvents("teacher.reply")).isZero();
    }

    // ---------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------

    private static Fixture fixture(MutableRandom random) {
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
        AtomicReference<Instant> clock = new AtomicReference<>(NOW);
        SessionContext context = new SessionContext(session.id(), ids::nextId, clock::get);
        ClassroomStateMachine classroom = new ClassroomStateMachine();
        InterviewStateMachine interview = new InterviewStateMachine(ids::nextId);
        FakeScheduler scheduler = new FakeScheduler();
        InMemorySessionEventStore eventStore = new InMemorySessionEventStore();
        InMemorySessionTextStore textStore = new InMemorySessionTextStore();
        SessionActor actor = new SessionActor(
                session, taskSnapshot, context, classroom, dynamics, interview,
                new ContentGenerationService(new FakeContentClient()), eventStore, textStore, ids,
                clock::get, scheduler, random);
        return new Fixture(actor, context, classroom, dynamics, textStore, scheduler, clock,
                taskSnapshot, random);
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
        private final AtomicReference<Instant> clock;
        private final TaskSnapshot taskSnapshot;
        private final MutableRandom random;
        private UUID studentId;

        private Fixture(
                SessionActor actor,
                SessionContext context,
                ClassroomStateMachine classroom,
                VirtualStudentDynamics dynamics,
                InMemorySessionTextStore textStore,
                FakeScheduler scheduler,
                AtomicReference<Instant> clock,
                TaskSnapshot taskSnapshot,
                MutableRandom random) {
            this.actor = actor;
            this.context = context;
            this.classroom = classroom;
            this.dynamics = dynamics;
            this.textStore = textStore;
            this.scheduler = scheduler;
            this.clock = clock;
            this.taskSnapshot = taskSnapshot;
            this.random = random;
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

        private FakeScheduler scheduler() {
            return scheduler;
        }

        private MutableRandom random() {
            return random;
        }

        private UUID studentId() {
            return studentId;
        }

        private void start() {
            assertThat(actor.handle(ready("ready-1")).ok()).isTrue();
            assertThat(actor.handle(new SessionCommand.StartSession("start-1")).ok()).isTrue();
        }

        private SessionCommand.CallOn callOn() {
            assertThat(actor.handle(new SessionCommand.AskClass("ask-1")).ok()).isTrue();
            scheduler.advance(Duration.ofSeconds(3));
            return callOnForLastRaise("call-1");
        }

        private SessionCommand.CallOn callOnStudentQuestion() {
            assertThat(actor.handle(
                    new SessionCommand.AskStudentQuestion("ask-student-1")).ok()).isTrue();
            scheduler.advance(Duration.ofSeconds(3));
            return callOnForLastRaise("call-student-1");
        }

        private SessionCommand.CallOn callOnForLastRaise(String commandId) {
            SessionEvent raise = lastEvent("student.hand_raised");
            studentId = (UUID) raise.payload().get("student_id");
            return new SessionCommand.CallOn(
                    commandId, studentId, (UUID) raise.payload().get("hand_raise_id"));
        }

        private UUID activeDialogueId() {
            return (UUID) lastEvent("dialogue.started").payload().get("dialogue_id");
        }

        private SessionEvent lastEvent(String type) {
            return context.after(0).stream()
                    .filter(event -> event.type().equals(type))
                    .reduce((first, second) -> second)
                    .orElseThrow();
        }

        private int countEvents(String type) {
            return (int) context.after(0).stream()
                    .filter(event -> event.type().equals(type))
                    .count();
        }

        private void advanceClock(Duration duration) {
            clock.updateAndGet(current -> current.plus(duration));
        }
    }
}
