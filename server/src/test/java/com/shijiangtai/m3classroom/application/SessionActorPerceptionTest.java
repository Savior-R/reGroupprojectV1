package com.shijiangtai.m3classroom.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.classroom.ClassroomMacroState;
import com.shijiangtai.m3classroom.domain.classroom.ClassroomStateMachine;
import com.shijiangtai.m3classroom.domain.classroom.VirtualStudentDynamics;
import com.shijiangtai.m3classroom.domain.interview.InterviewStateMachine;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionContext;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.m3classroom.support.FakeContentClient;
import com.shijiangtai.m3classroom.support.FakeScheduler;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionTextStore;
import com.shijiangtai.shared.application.ClockProvider;
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

/** Perception hooks on {@link SessionActor}: speech finals, voice QA and screen events. */
class SessionActorPerceptionTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
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

    private static final String SECRET_REFERENCE_TEXT = "SECRET_REFERENCE_TEXT";
    private static final String SECRET_PERSONALITY_TEXT = "SECRET_PERSONALITY_TEXT";
    private static final String SECRET_ANSWER_POINTS = "SECRET_ANSWER_POINTS";

    @Test
    void highConfidenceClassifiedQuestionStartsVoiceQaAndSchedulesHands() {
        ActorFixture fixture = fixture(TaskMode.FREE, new FakeContentClient(), () -> NOW);
        startClassroom(fixture);

        fixture.actor().onTeacherSpeechClassified(0.9);

        List<SessionEvent> events = fixture.context().after(0);
        assertThat(events).extracting(SessionEvent::type)
                .containsSubsequence("classroom.question_asked", "classroom.state_changed");
        SessionEvent asked = events.stream()
                .filter(e -> e.type().equals("classroom.question_asked"))
                .findFirst().orElseThrow();
        assertThat(asked.payload().get("source")).isEqualTo("voice");
        assertThat(asked.payload().get("intent")).isEqualTo("answer");
        assertThat(fixture.scheduler().pendingTasks()).isEqualTo(2); // hand raise + window timeout
        assertThat(fixture.classroom().macroState()).isEqualTo(ClassroomMacroState.QA);

        fixture.scheduler().advance(Duration.ofSeconds(3));

        assertThat(eventsOfType(fixture, "student.hand_raised")).isNotEmpty();
        assertThat(fixture.scheduler().pendingTasks()).isEqualTo(1);
    }

    @Test
    void lowConfidenceClassifiedQuestionOnlySuggests() {
        ActorFixture fixture = fixture(TaskMode.FREE, new FakeContentClient(), () -> NOW);
        startClassroom(fixture);

        fixture.actor().onTeacherSpeechClassified(0.4);

        assertThat(fixture.context().after(0)).extracting(SessionEvent::type)
                .contains("classroom.question_suggested")
                .doesNotContain("classroom.question_asked");
        assertThat(fixture.classroom().macroState()).isEqualTo(ClassroomMacroState.LECTURING);
        assertThat(fixture.scheduler().pendingTasks()).isZero();
    }

    @Test
    void classifiedQuestionIsIgnoredInInterviewModeAndAfterTerminal() {
        ActorFixture interview = fixture(TaskMode.INTERVIEW, new FakeContentClient(), () -> NOW);
        startInterview(interview);
        int interviewEvents = interview.context().after(0).size();

        interview.actor().onTeacherSpeechClassified(0.9);

        assertThat(interview.context().after(0)).hasSize(interviewEvents);
        assertThat(interview.scheduler().pendingTasks()).isZero();

        ActorFixture ended = fixture(TaskMode.FREE, new FakeContentClient(), () -> NOW);
        startClassroom(ended);
        assertThat(ended.actor().handle(new SessionCommand.EndSession("end-1")).ok()).isTrue();
        int endedEvents = ended.context().after(0).size();

        ended.actor().onTeacherSpeechClassified(0.9);

        assertThat(ended.context().after(0)).hasSize(endedEvents);
        assertThat(ended.scheduler().pendingTasks()).isZero();
    }

    @Test
    void recordSpeechFinalUsesKindAndAnswerIdForInterview() {
        ActorFixture fixture = fixture(TaskMode.INTERVIEW, new FakeContentClient(), () -> NOW);
        startInterview(fixture);
        UUID answerId = fixture.actor().currentAnswerId();
        assertThat(answerId).isNotNull();

        UUID textId = fixture.actor()
                .recordSpeechFinal("candidate", answerId, "我的回答", 0L, 1200L,
                        new BigDecimal("0.91"))
                .orElseThrow();

        SessionText text = fixture.textStore().all().stream()
                .filter(t -> t.id().equals(textId))
                .findFirst().orElseThrow();
        assertThat(text.kind()).isEqualTo("answer");
        assertThat(text.speakerType()).isEqualTo("candidate");
        assertThat(text.speakerRef()).isEqualTo(answerId);
        assertThat(text.startMs()).isZero();
        assertThat(text.endMs()).isEqualTo(1200L);
        assertThat(text.source()).isEqualTo("asr");
        assertThat(text.status()).isEqualTo("final");
        assertThat(text.atMs()).isEqualTo(1200L);
        assertThat(text.confidence()).isEqualByComparingTo("0.91");
        assertThat(text.modelVersion()).isNull();
        assertThat(text.promptVersion()).isNull();
        assertThat(fixture.actor().currentAnswerId()).isEqualTo(answerId);
    }

    @Test
    void recordSpeechFinalStoresClassroomUtteranceAndSkipsBlankOrInactive() {
        MutableClock clock = new MutableClock(NOW);
        ActorFixture fixture = fixture(TaskMode.FREE, new FakeContentClient(), clock);

        assertThat(fixture.actor()
                .recordSpeechFinal("teacher", null, "开始前", null, null, BigDecimal.ONE))
                .isEmpty();
        assertThat(fixture.actor().currentAnswerId()).isNull();

        startClassroom(fixture);
        clock.advance(Duration.ofSeconds(5));
        UUID textId = fixture.actor()
                .recordSpeechFinal("teacher", null, "同学们看这里", null, null,
                        new BigDecimal("0.75"))
                .orElseThrow();

        SessionText text = fixture.textStore().all().stream()
                .filter(t -> t.id().equals(textId))
                .findFirst().orElseThrow();
        assertThat(text.kind()).isEqualTo("utterance");
        assertThat(text.speakerType()).isEqualTo("teacher");
        assertThat(text.source()).isEqualTo("asr");
        assertThat(text.status()).isEqualTo("final");
        assertThat(text.startMs()).isNull();
        assertThat(text.endMs()).isNull();
        assertThat(text.atMs()).isEqualTo(5_000L);

        int stored = fixture.textStore().size();
        assertThat(fixture.actor()
                .recordSpeechFinal("teacher", null, "   ", 0L, 10L, BigDecimal.ONE))
                .isEmpty();
        assertThat(fixture.textStore().size()).isEqualTo(stored);

        assertThat(fixture.actor().handle(new SessionCommand.EndSession("end-1")).ok()).isTrue();
        assertThat(fixture.actor()
                .recordSpeechFinal("teacher", null, "结束后", 0L, 10L, BigDecimal.ONE))
                .isEmpty();
        assertThat(fixture.textStore().size()).isEqualTo(stored);
    }

    @Test
    void screenProcessedEmitsPerceptionEventOnlyWhileInProgress() {
        ActorFixture fixture = fixture(TaskMode.FREE, new FakeContentClient(), () -> NOW);
        UUID frameId = UUID.randomUUID();

        fixture.actor().onScreenProcessed(frameId, 1500, "slide_change");
        assertThat(eventsOfType(fixture, "perception.screen_changed")).isEmpty();

        startClassroom(fixture);
        fixture.actor().onScreenProcessed(frameId, 1500, "slide_change");

        SessionEvent event = eventsOfType(fixture, "perception.screen_changed").get(0);
        assertThat(event.payload())
                .containsEntry("frame_id", frameId)
                .containsEntry("at_ms", 1500L)
                .containsEntry("frame_type", "slide_change");

        assertThat(fixture.actor().handle(new SessionCommand.EndSession("end-1")).ok()).isTrue();
        fixture.actor().onScreenProcessed(UUID.randomUUID(), 2000, "normal");
        assertThat(eventsOfType(fixture, "perception.screen_changed")).hasSize(1);
    }

    @Test
    void addStatusListenerReceivesStatusChangesWithoutReplacingPrimaryListener() {
        ActorFixture fixture = fixture(TaskMode.FREE, new FakeContentClient(), () -> NOW);
        List<SessionStatus> primary = new ArrayList<>();
        List<SessionStatus> added = new ArrayList<>();
        fixture.actor().attachStatusListener(primary::add);
        Runnable remove = fixture.actor().addStatusListener(added::add);

        startClassroom(fixture);
        fixture.actor().handle(new SessionCommand.EndSession("end-1"));

        assertThat(primary).containsExactly(SessionStatus.IN_PROGRESS, SessionStatus.ENDED);
        assertThat(added).containsExactly(SessionStatus.IN_PROGRESS, SessionStatus.ENDED);

        remove.run();
        fixture.actor().fail(FailureReason.RECORDING_LOST);

        assertThat(primary)
                .containsExactly(SessionStatus.IN_PROGRESS, SessionStatus.ENDED,
                        SessionStatus.FAILED);
        assertThat(added).containsExactly(SessionStatus.IN_PROGRESS, SessionStatus.ENDED);
    }

    // ---------------------------------------------------------------------
    // Fixture helpers (same shape as SessionActorTest)
    // ---------------------------------------------------------------------

    private static void startClassroom(ActorFixture fixture) {
        fixture.actor().handle(ready("ready-1"));
        assertThat(fixture.actor().handle(new SessionCommand.StartSession("start-1")).ok())
                .isTrue();
    }

    private static void startInterview(ActorFixture fixture) {
        fixture.actor().handle(ready("ready-1", StreamType.CAMERA, StreamType.AUDIO));
        assertThat(fixture.actor().handle(new SessionCommand.StartSession("start-1")).ok())
                .isTrue();
    }

    private static ActorFixture fixture(TaskMode mode, FakeContentClient client, ClockProvider clock) {
        UUID sessionUuid = UUID.randomUUID();
        Session session = Session.createPreparing(
                new SessionId(sessionUuid), SessionType.TASK, new TaskId(TASK_ID),
                new CourseId(COURSE_ID), new AccountId(STUDENT_ACCOUNT_ID), mode, 1, NOW);
        TaskSnapshot taskSnapshot = taskSnapshot(mode);
        VirtualStudentDynamics dynamics = new VirtualStudentDynamics(new java.util.Random(7));
        dynamics.initialize(taskSnapshot.virtualStudents().stream()
                .map(SessionActorPerceptionTest::toConfig)
                .toList());
        IdGenerator ids = () -> UUID.randomUUID();
        SessionContext context = new SessionContext(session.id(), ids::nextId, () -> NOW);
        ClassroomStateMachine classroom = new ClassroomStateMachine();
        InterviewStateMachine interview = new InterviewStateMachine(ids::nextId);
        FakeScheduler scheduler = new FakeScheduler();
        InMemorySessionEventStore eventStore = new InMemorySessionEventStore();
        InMemorySessionTextStore textStore = new InMemorySessionTextStore();
        SessionActor actor = new SessionActor(
                session, taskSnapshot, context, classroom, dynamics, interview,
                new ContentGenerationService(client), eventStore, textStore, ids,
                clock, scheduler, new java.util.Random(11));
        return new ActorFixture(actor, session, taskSnapshot, context, scheduler, client,
                eventStore, textStore, dynamics, classroom, interview);
    }

    private static TaskSnapshot taskSnapshot(TaskMode mode) {
        List<TaskSnapshot.VirtualStudentSnapshot> students = mode == TaskMode.INTERVIEW
                ? List.of()
                : List.of(virtualStudent(VIRTUAL_STUDENT_1, "林小雨", 1),
                        virtualStudent(VIRTUAL_STUDENT_2, "陈晨", 2));
        TaskSnapshot.InterviewSnapshot interview = mode == TaskMode.INTERVIEW
                ? new TaskSnapshot.InterviewSnapshot(300, 2, List.of(
                        question(1, "请谈谈荷塘月色的写作背景。"),
                        question(2, "你如何设计这堂课的教学目标？")))
                : null;
        return new TaskSnapshot(
                1, TASK_ID, COURSE_ID, SCHOOL_ID, mode.wireValue(), "荷塘月色", "部编版",
                new TaskSnapshot.DurationSnapshot(900, 1200),
                Map.of("content", 40, "expression", 30, "interaction", 30),
                new TaskSnapshot.MaterialSnapshot(MATERIAL_ID, "SECRET_MATERIAL_FILENAME.pdf",
                        "materials/secret", "application/pdf", 1024, SECRET_REFERENCE_TEXT,
                        SECRET_REFERENCE_TEXT.length()),
                new TaskSnapshot.ScenarioSnapshot("班主任", "学生上课玩手机"),
                students, interview, NOW, STUDENT_ACCOUNT_ID);
    }

    private static TaskSnapshot.VirtualStudentSnapshot virtualStudent(
            UUID id, String name, int seatNo) {
        return new TaskSnapshot.VirtualStudentSnapshot(
                id, name, "avatar-0" + seatNo, SECRET_PERSONALITY_TEXT,
                new BigDecimal("0.60"), new BigDecimal("0.50"), new BigDecimal("0.70"),
                new BigDecimal("0.50"), new BigDecimal("0.40"), seatNo, seatNo);
    }

    private static TaskSnapshot.QuestionSnapshot question(int order, String stem) {
        return new TaskSnapshot.QuestionSnapshot(
                UUID.nameUUIDFromBytes(("question-" + order).getBytes()), order, stem,
                SECRET_ANSWER_POINTS, null, null);
    }

    private static VirtualStudentConfig toConfig(TaskSnapshot.VirtualStudentSnapshot student) {
        return new VirtualStudentConfig(
                student.virtualStudentId(), student.name(), student.avatar(),
                student.personalityText(), student.activity(), student.questionRate(),
                student.ability(), student.attentionBaseline(), student.disciplineProbability(),
                student.seatNo(), student.sortOrder());
    }

    private static SessionCommand.RecordingReady ready(String commandId) {
        return ready(commandId, StreamType.values());
    }

    private static SessionCommand.RecordingReady ready(String commandId, StreamType... readyOnes) {
        List<StreamType> ready = List.of(readyOnes);
        List<SessionCommand.RecordingReady.StreamStatus> streams = new ArrayList<>();
        for (StreamType type : StreamType.values()) {
            streams.add(new SessionCommand.RecordingReady.StreamStatus(
                    type, ready.contains(type) ? "ready" : "unavailable"));
        }
        return new SessionCommand.RecordingReady(commandId, streams);
    }

    private static List<SessionEvent> eventsOfType(ActorFixture fixture, String type) {
        return fixture.context().after(0).stream()
                .filter(event -> event.type().equals(type))
                .toList();
    }

    private static final class MutableClock implements ClockProvider {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant now() {
            return now;
        }
    }

    private record ActorFixture(
            SessionActor actor,
            Session session,
            TaskSnapshot task,
            SessionContext context,
            FakeScheduler scheduler,
            FakeContentClient contentClient,
            InMemorySessionEventStore eventStore,
            InMemorySessionTextStore textStore,
            VirtualStudentDynamics dynamics,
            ClassroomStateMachine classroom,
            InterviewStateMachine interview) {
    }
}
