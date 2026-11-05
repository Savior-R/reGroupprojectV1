package com.shijiangtai.m3classroom.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.application.port.ContentResponse;
import com.shijiangtai.m3classroom.domain.classroom.ClassroomMacroState;
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
import com.shijiangtai.m3classroom.domain.session.SessionSnapshot;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.m3classroom.infrastructure.time.UuidIdGenerator;
import com.shijiangtai.m3classroom.support.FakeContentClient;
import com.shijiangtai.m3classroom.support.FakeScheduler;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionTextStore;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class SessionActorTest {

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

    private static final String SECRET_REFERENCE_TEXT = "SECRET_REFERENCE_TEXT";
    private static final String SECRET_PERSONALITY_TEXT = "SECRET_PERSONALITY_TEXT";
    private static final String SECRET_ANSWER_POINTS = "SECRET_ANSWER_POINTS";

    @Test
    void duplicateCommandIdExecutesOnce() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        assertThat(fixture.actor().handle(ready("ready-1")).ok()).isTrue();
        SessionEvent started = fixture.actor().handle(new SessionCommand.StartSession("start-1"))
                .events().get(0);
        int persistedAfterFirst = fixture.eventStore().size();
        long seqAfterFirst = fixture.context().lastSeq();

        SessionCommand.StartSession duplicate = new SessionCommand.StartSession("start-1");
        var ack = fixture.actor().handle(duplicate);

        assertThat(ack.ok()).isTrue();
        assertThat(ack.events()).containsExactly(started);
        assertThat(fixture.eventStore().size()).isEqualTo(persistedAfterFirst);
        assertThat(fixture.context().lastSeq()).isEqualTo(seqAfterFirst);
        assertThat(eventsOfType(fixture, "session.started")).hasSize(1);
    }

    @Test
    void seqIsMonotonicAcrossCommands() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));

        List<SessionEvent> events = fixture.context().after(0);
        List<Long> seqs = events.stream().map(SessionEvent::seq).toList();

        assertThat(seqs).containsExactly(1L, 2L, 3L, 4L);
        assertThat(fixture.context().lastSeq()).isEqualTo(4L);
        assertThat(fixture.eventStore().all()).hasSize(events.size());
    }

    @Test
    void illegalCommandLeavesSnapshotUnchanged() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        SessionSnapshot before = fixture.actor().snapshot();

        var result = fixture.actor().handle(new SessionCommand.StartSession("start-1"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ErrorCode.SESSION_STATE_INVALID);
        assertThat(fixture.actor().snapshot()).isEqualTo(before);
        assertThat(fixture.actor().snapshot().status()).isEqualTo(SessionStatus.PREPARING);
        assertThat(fixture.context().lastSeq()).isZero();
        assertThat(fixture.eventStore().size()).isZero();
    }

    @Test
    void startRequiresRecordingReady() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        var partialAck = fixture.actor().handle(ready("ready-partial", StreamType.CAMERA));

        assertThat(partialAck.ok()).isTrue();
        assertThat(partialAck.events().get(0).payload().get("ok")).isEqualTo(false);

        var rejected = fixture.actor().handle(new SessionCommand.StartSession("start-too-early"));
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.SESSION_STATE_INVALID);
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.PREPARING);
        assertThat(fixture.context().lastSeq()).isEqualTo(1L);

        var fullAck = fixture.actor().handle(ready("ready-full"));
        assertThat(fullAck.events().get(0).payload().get("ok")).isEqualTo(true);

        var started = fixture.actor().handle(new SessionCommand.StartSession("start-now"));
        assertThat(started.ok()).isTrue();
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(eventsOfType(fixture, "session.started")).hasSize(1);
    }

    @Test
    void recordingReadyAckListsRequiredStreams() {
        ActorFixture free = freeFixture(FakeContentClient::new);
        SessionEvent freeAck = free.actor().handle(ready("ready-free")).events().get(0);
        assertThat(freeAck.type()).isEqualTo("recording.ready_ack");
        assertThat(freeAck.payload().get("required_streams"))
                .isEqualTo(List.of("camera", "screen", "audio"));
        assertThat(freeAck.payload().get("ok")).isEqualTo(true);

        ActorFixture interview = interviewFixture(FakeContentClient::new);
        SessionEvent interviewAck = interview.actor()
                .handle(ready("ready-interview", StreamType.CAMERA, StreamType.AUDIO))
                .events().get(0);
        assertThat(interviewAck.payload().get("required_streams"))
                .isEqualTo(List.of("camera", "audio"));
        assertThat(interviewAck.payload().get("ok")).isEqualTo(true);
    }

    @Test
    void snapshotHidesPrivilegedConfig() {
        ActorFixture free = freeFixture(FakeContentClient::new);
        free.actor().handle(ready("ready-free"));
        free.actor().handle(new SessionCommand.StartSession("start-free"));
        SessionSnapshot snapshot = free.actor().snapshot();

        Set<String> keys = collectKeys(snapshot);
        Set<String> values = collectValues(snapshot);
        // Guard: the reflective walker must actually see record components and nested maps.
        assertThat(keys).contains("status", "publicConfig", "virtualStudents", "topic");
        assertThat(keys.size()).isGreaterThan(5);
        assertThat(values).isNotEmpty();
        assertThat(values).contains("荷塘月色");

        assertThat(keys).doesNotContain(
                "weights", "rubric_weights", "reference_material", "material",
                "parsed_text", "object_key", "answer_points", "personality",
                "personality_text", "ability", "question_rate", "activity",
                "attention_baseline", "discipline_probability");
        assertThat(values).doesNotContain(
                SECRET_REFERENCE_TEXT, SECRET_PERSONALITY_TEXT, SECRET_ANSWER_POINTS,
                "SECRET_MATERIAL_FILENAME.pdf", "materials/secret");
        assertThat(snapshot.virtualStudents()).isNotEmpty();
        assertThat(snapshot.publicConfig()).containsKey("topic");

        ActorFixture interview = interviewFixture(FakeContentClient::new);
        interview.actor().handle(ready("ready-interview", StreamType.CAMERA, StreamType.AUDIO));
        interview.actor().handle(new SessionCommand.StartSession("start-interview"));
        SessionSnapshot interviewSnapshot = interview.actor().snapshot();
        Set<String> interviewKeys = collectKeys(interviewSnapshot);
        Set<String> interviewValues = collectValues(interviewSnapshot);
        assertThat(interviewKeys).contains("interview", "current");
        assertThat(interviewValues).isNotEmpty();
        assertThat(interviewKeys).doesNotContain(
                "weights", "rubric_weights", "reference_material", "material",
                "parsed_text", "object_key", "answer_points", "personality_text");
        assertThat(interviewValues).doesNotContain(SECRET_ANSWER_POINTS);
        assertThat(interviewSnapshot.interview()).containsKey("current");
    }

    @Test
    void eventSinkReceivesOnlyNewEvents() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));

        List<SessionEvent> received = new CopyOnWriteArrayList<>();
        fixture.actor().attachEventSink(received::add);
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).type()).isEqualTo("session.started");
        assertThat(received.get(0).seq()).isEqualTo(2L);

        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        assertThat(received).hasSize(3);
        assertThat(received).allMatch(event -> event.seq() > 1L);
        assertThat(received.stream().map(SessionEvent::seq).distinct().count())
                .isEqualTo(received.size());
    }

    @Test
    void fiftyConcurrentCommandsHaveUniqueSeq() throws Exception {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                String commandId = "ask-" + i;
                futures.add(pool.submit(
                        () -> fixture.actor().handle(new SessionCommand.AskClass(commandId))));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        List<SessionEvent> events = fixture.context().after(0);
        List<Long> seqs = events.stream().map(SessionEvent::seq).toList();
        assertThat(seqs).hasSize(2 + 50 * 2);
        assertThat(seqs).isSorted();
        assertThat(new HashSet<>(seqs)).hasSameSizeAs(seqs);
        assertThat(fixture.context().lastSeq()).isEqualTo(events.size());
    }

    @Test
    void idGeneratorProducesUuidVersion7() {
        assertThat(new UuidIdGenerator().nextId().version()).isEqualTo(7);
    }

    @Test
    void askClassSchedulesHandRaisesAndEachIsAppliedOnce() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));

        assertThat(eventsOfType(fixture, "student.hand_raised")).isEmpty();
        fixture.scheduler().advance(Duration.ofSeconds(3));

        List<SessionEvent> raises = eventsOfType(fixture, "student.hand_raised");
        assertThat(raises).hasSizeBetween(1, 3);
        for (SessionEvent raise : raises) {
            UUID studentId = (UUID) raise.payload().get("student_id");
            assertThat(fixture.dynamics().find(studentId).cooldownUntil()).isNotNull();
        }
        long uniqueStudents = raises.stream()
                .map(event -> event.payload().get("student_id"))
                .distinct()
                .count();
        assertThat(uniqueStudents).isEqualTo(raises.size());
    }

    @Test
    void handRaiseIsVisibleInSnapshotAndTickStates() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));

        List<SessionEvent> raises = eventsOfType(fixture, "student.hand_raised");
        assertThat(raises).isNotEmpty();
        List<UUID> raisedStudentIds = raises.stream()
                .map(event -> (UUID) event.payload().get("student_id"))
                .toList();

        SessionSnapshot snapshot = fixture.actor().snapshot();
        for (UUID studentId : raisedStudentIds) {
            assertThat(snapshot.virtualStudents())
                    .filteredOn(view -> studentId.equals(view.get("virtual_student_id")))
                    .singleElement()
                    .satisfies(view -> assertThat(view.get("state")).isEqualTo("hand_raised"));
            assertThat(fixture.dynamics().find(studentId).behavior())
                    .isEqualTo(VirtualStudentBehavior.HAND_RAISED);
        }
        assertThat(eventsOfType(fixture, "student.state_changed"))
                .filteredOn(event -> "hand_raised".equals(event.payload().get("reason")))
                .hasSize(raises.size());

        fixture.actor().onTick(NOW.plusMillis(1_000));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tickStates = (List<Map<String, Object>>)
                eventsOfType(fixture, "session.time_tick").get(0).payload().get("states");
        for (UUID studentId : raisedStudentIds) {
            assertThat(tickStates)
                    .filteredOn(state -> studentId.equals(state.get("virtual_student_id")))
                    .singleElement()
                    .satisfies(state -> assertThat(state.get("state")).isEqualTo("hand_raised"));
        }
    }

    @Test
    void callOnTransitionsToCalledOnAndAnsweringThenFocused() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));
        SessionEvent raise = eventsOfType(fixture, "student.hand_raised").get(0);
        UUID studentId = (UUID) raise.payload().get("student_id");

        assertThat(fixture.actor().handle(new SessionCommand.CallOn(
                "call-1", studentId, (UUID) raise.payload().get("hand_raise_id"))).ok())
                .isTrue();

        List<SessionEvent> transitions = eventsOfType(fixture, "student.state_changed").stream()
                .filter(event -> studentId.equals(event.payload().get("student_id")))
                .toList();
        assertThat(transitions).extracting(event -> event.payload().get("to"))
                .containsExactly("hand_raised", "called_on", "answering", "focused");
        assertThat(transitions).extracting(event -> event.payload().get("reason"))
                .containsExactly(
                        "hand_raised", "called_on", "answer_started", "answer_finished");
        assertThat(fixture.actor().snapshot().virtualStudents())
                .filteredOn(view -> studentId.equals(view.get("virtual_student_id")))
                .singleElement()
                .satisfies(view -> assertThat(view.get("state")).isEqualTo("focused"));
    }

    @Test
    void studentQuestionCallOnKeepsCalledOnState() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskStudentQuestion("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));
        SessionEvent raise = eventsOfType(fixture, "student.hand_raised").get(0);
        UUID studentId = (UUID) raise.payload().get("student_id");

        assertThat(fixture.actor().handle(new SessionCommand.CallOn(
                "call-1", studentId, (UUID) raise.payload().get("hand_raise_id"))).ok())
                .isTrue();

        assertThat(fixture.actor().snapshot().virtualStudents())
                .filteredOn(view -> studentId.equals(view.get("virtual_student_id")))
                .singleElement()
                .satisfies(view -> assertThat(view.get("state")).isEqualTo("called_on"));
        assertThat(eventsOfType(fixture, "student.question")).hasSize(1);
        assertThat(eventsOfType(fixture, "student.question").get(0).payload())
                .containsKey("question_id");
    }

    @Test
    void emptyHandWindowTimesOutWhenOnlyStudentIsIneligible() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.dynamics().initialize(List.of(toConfig(
                virtualStudent(VIRTUAL_STUDENT_1, "林小雨", 1))));
        fixture.dynamics().applyStateChanged(
                VIRTUAL_STUDENT_1, VirtualStudentBehavior.WHISPERING);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));

        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        assertThat(fixture.scheduler().pendingTasks()).isEqualTo(2);
        fixture.scheduler().advance(Duration.ofSeconds(3));

        assertThat(eventsOfType(fixture, "student.hand_raised")).isEmpty();
        fixture.scheduler().advance(Duration.ofSeconds(27));

        List<SessionEvent> timeouts = eventsOfType(fixture, "classroom.hand_window_timeout");
        assertThat(timeouts).hasSize(1);
        assertThat(timeouts.get(0).payload().get("hand_raise_ids")).isEqualTo(List.of());
        assertThat(fixture.classroom().macroState()).isEqualTo(ClassroomMacroState.LECTURING);
        List<SessionEvent> classroomChanges = eventsOfType(fixture, "classroom.state_changed");
        assertThat(classroomChanges.get(classroomChanges.size() - 1).payload().get("trigger"))
                .isEqualTo("hand_window_timeout");
        assertThat(eventsOfType(fixture, "student.hand_lowered")).isEmpty();
    }

    @Test
    void snapshotElapsedIsClampedToEndedAt() {
        MutableClock clock = new MutableClock(NOW);
        ActorFixture fixture = fixture(TaskMode.FREE, new FakeContentClient(), clock);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        clock.advance(Duration.ofSeconds(60));
        fixture.actor().handle(new SessionCommand.EndSession("end-1"));

        clock.advance(Duration.ofSeconds(600));

        assertThat(fixture.actor().snapshot().elapsedMs()).isEqualTo(60_000L);
    }

    @Test
    void handWindowTimeoutClosesWindowWithRaisedIds() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));

        List<Object> raisedIds = eventsOfType(fixture, "student.hand_raised").stream()
                .map(event -> event.payload().get("hand_raise_id"))
                .toList();
        assertThat(raisedIds).isNotEmpty();

        fixture.scheduler().advance(Duration.ofSeconds(31));

        List<SessionEvent> timeouts = eventsOfType(fixture, "classroom.hand_window_timeout");
        assertThat(timeouts).hasSize(1);
        assertThat(timeouts.get(0).payload().get("hand_raise_ids"))
                .isEqualTo(raisedIds);
        assertThat(fixture.classroom().macroState()).isEqualTo(ClassroomMacroState.LECTURING);

        List<SessionEvent> lowers = eventsOfType(fixture, "student.hand_lowered");
        assertThat(lowers).hasSameSizeAs(raisedIds);
        for (SessionEvent lower : lowers) {
            assertThat(lower.payload().get("reason")).isEqualTo("teacher_timeout");
            assertThat(raisedIds).contains(lower.payload().get("hand_raise_id"));
        }
        assertThat(indexOfType(fixture, "student.hand_lowered"))
                .isLessThan(indexOfType(fixture, "classroom.hand_window_timeout"));
    }

    @Test
    void callOnLowersOtherOpenHands() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));

        List<SessionEvent> raises = eventsOfType(fixture, "student.hand_raised");
        assertThat(raises).hasSize(2);
        UUID calledStudent = (UUID) raises.get(0).payload().get("student_id");
        UUID calledHand = (UUID) raises.get(0).payload().get("hand_raise_id");
        UUID otherHand = (UUID) raises.get(1).payload().get("hand_raise_id");

        var result = fixture.actor().handle(
                new SessionCommand.CallOn("call-1", calledStudent, calledHand));

        assertThat(result.ok()).isTrue();
        List<SessionEvent> lowers = eventsOfType(fixture, "student.hand_lowered");
        assertThat(lowers).hasSize(1);
        assertThat(lowers.get(0).payload().get("hand_raise_id")).isEqualTo(otherHand);
        assertThat(lowers.get(0).payload().get("reason")).isEqualTo("called_on");
        assertThat(lowers).noneMatch(
                event -> calledHand.equals(event.payload().get("hand_raise_id")));
    }

    @Test
    void illegalEndDialogueKeepsHandWindowTimerAndHands() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));
        int raisedCount = eventsOfType(fixture, "student.hand_raised").size();
        assertThat(raisedCount).isPositive();
        assertThat(fixture.scheduler().pendingTasks()).isEqualTo(1);

        var result = fixture.actor().handle(
                new SessionCommand.EndDialogue("end-1", UUID.randomUUID()));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ErrorCode.SESSION_STATE_INVALID);
        assertThat(fixture.scheduler().pendingTasks()).isEqualTo(1);

        fixture.scheduler().advance(Duration.ofSeconds(31));
        assertThat(eventsOfType(fixture, "classroom.hand_window_timeout")).hasSize(1);
        assertThat(eventsOfType(fixture, "student.hand_lowered")).hasSize(raisedCount);
    }

    @Test
    void answerFinishesAndReturnsToWaitingHands() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));
        SessionEvent raise = eventsOfType(fixture, "student.hand_raised").get(0);

        var result = fixture.actor().handle(new SessionCommand.CallOn(
                "call-1", (UUID) raise.payload().get("student_id"),
                (UUID) raise.payload().get("hand_raise_id")));

        assertThat(result.ok()).isTrue();
        assertThat(fixture.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        List<SessionEvent> stateChanges = eventsOfType(fixture, "classroom.state_changed");
        assertThat(stateChanges.get(stateChanges.size() - 1).payload().get("trigger"))
                .isEqualTo("answer_finished");
    }

    @Test
    void endDialogueFinishesAnswerWhileStudentAnswering() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));
        SessionEvent raise = eventsOfType(fixture, "student.hand_raised").get(0);
        UUID studentId = (UUID) raise.payload().get("student_id");
        fixture.actor().handle(new SessionCommand.CallOn(
                "call-1", studentId, (UUID) raise.payload().get("hand_raise_id")));

        // Put the domain back into student_answering to exercise the explicit-finish branch.
        fixture.classroom().callOn(studentId, NOW);
        assertThat(fixture.classroom().qaPhase()).isEqualTo(QaPhase.STUDENT_ANSWERING);

        var result = fixture.actor().handle(
                new SessionCommand.EndDialogue("end-1", UUID.randomUUID()));

        assertThat(result.ok()).isTrue();
        assertThat(fixture.classroom().qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        List<SessionEvent> stateChanges = eventsOfType(fixture, "classroom.state_changed");
        assertThat(stateChanges.get(stateChanges.size() - 1).payload().get("trigger"))
                .isEqualTo("answer_finished");
    }

    @Test
    void endQaLowersOpenHands() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));
        int raisedCount = eventsOfType(fixture, "student.hand_raised").size();

        var result = fixture.actor().handle(new SessionCommand.EndQa("end-qa-1"));

        assertThat(result.ok()).isTrue();
        assertThat(fixture.classroom().macroState()).isEqualTo(ClassroomMacroState.LECTURING);
        List<SessionEvent> lowers = eventsOfType(fixture, "student.hand_lowered");
        assertThat(lowers).hasSize(raisedCount);
        assertThat(lowers).allMatch(
                event -> "qa_ended".equals(event.payload().get("reason")));
    }

    @Test
    void manageStudentEmitsManagedStateChanged() {
        ActorFixture fixture = freeFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.dynamics().applyStateChanged(VIRTUAL_STUDENT_1, VirtualStudentBehavior.DISTRACTED);

        var result = fixture.actor().handle(
                new SessionCommand.ManageStudent("manage-1", VIRTUAL_STUDENT_1, "remind"));

        assertThat(result.ok()).isTrue();
        assertThat(eventsOfType(fixture, "teacher.management_action")).hasSize(1);
        SessionEvent stateChanged = eventsOfType(fixture, "student.state_changed").get(0);
        assertThat(stateChanged.payload().get("student_id")).isEqualTo(VIRTUAL_STUDENT_1);
        assertThat(stateChanged.payload().get("reason")).isEqualTo("teacher_manage");
        assertThat(fixture.dynamics().find(VIRTUAL_STUDENT_1).behavior())
                .isEqualTo(VirtualStudentBehavior.FOCUSED);
    }

    @Test
    void callOnStudentQuestionGeneratesTextAndAppliesDynamics() {
        FakeContentClient client = new FakeContentClient().withQuestionResponse(
                new ContentResponse("老师，这句话怎么理解？", 0.9, "fake-model", "prompt-v1"));
        ActorFixture fixture = freeFixture(() -> client);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskStudentQuestion("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));

        SessionEvent raise = eventsOfType(fixture, "student.hand_raised").get(0);
        UUID studentId = (UUID) raise.payload().get("student_id");
        UUID handRaiseId = (UUID) raise.payload().get("hand_raise_id");
        double attentionBefore = fixture.dynamics().find(studentId).attention();

        var result = fixture.actor().handle(
                new SessionCommand.CallOn("call-1", studentId, handRaiseId));

        assertThat(result.ok()).isTrue();
        assertThat(eventsOfType(fixture, "student.question")).hasSize(1);
        assertThat(fixture.dynamics().find(studentId).attention())
                .isGreaterThan(attentionBefore);
        assertThat(client.questionRequests()).hasSize(1);
        List<com.shijiangtai.m3classroom.domain.session.SessionText> texts =
                fixture.textStore().bySession(fixture.session().id(), "student");
        assertThat(texts).hasSize(1);
        assertThat(texts.get(0).text()).isEqualTo("老师，这句话怎么理解？");
        assertThat(texts.get(0).kind()).isEqualTo("question");
    }

    @Test
    void contentFallbackPersistsTemplateWhenClientFails() {
        FakeContentClient client = FakeContentClient.failing();
        ActorFixture fixture = freeFixture(() -> client);
        fixture.actor().handle(ready("ready-1"));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));
        fixture.actor().handle(new SessionCommand.AskClass("ask-1"));
        fixture.scheduler().advance(Duration.ofSeconds(3));

        SessionEvent raise = eventsOfType(fixture, "student.hand_raised").get(0);
        UUID studentId = (UUID) raise.payload().get("student_id");
        UUID handRaiseId = (UUID) raise.payload().get("hand_raise_id");

        var result = fixture.actor().handle(
                new SessionCommand.CallOn("call-1", studentId, handRaiseId));

        assertThat(result.ok()).isTrue();
        List<com.shijiangtai.m3classroom.domain.session.SessionText> texts =
                fixture.textStore().bySession(fixture.session().id(), "student");
        assertThat(texts).hasSize(1);
        assertThat(texts.get(0).text()).isEqualTo("老师，我认为这一段的重点是……");
        assertThat(texts.get(0).confidence()).isEqualByComparingTo("0.0");
        assertThat(texts.get(0).source()).isEqualTo("llm");
        assertThat(eventsOfType(fixture, "student.answer")).hasSize(1);
    }

    @Test
    void interviewStartEmitsQuestionShownAndAnswerStarted() {
        ActorFixture fixture = interviewFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1", StreamType.CAMERA, StreamType.AUDIO));

        var result = fixture.actor().handle(new SessionCommand.StartSession("start-1"));

        assertThat(result.ok()).isTrue();
        assertThat(fixture.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(eventsOfType(fixture, "session.started")).hasSize(1);
        assertThat(eventsOfType(fixture, "interview.question_shown")).hasSize(1);
        assertThat(eventsOfType(fixture, "interview.answer_started")).hasSize(1);
        assertThat(eventsOfType(fixture, "interview.question_shown").get(0).payload()
                .get("text")).isEqualTo("请谈谈荷塘月色的写作背景。");
    }

    @Test
    void interviewerTextReferencesQuestionId() {
        ActorFixture fixture = interviewFixture(FakeContentClient::new);
        fixture.actor().handle(ready("ready-1", StreamType.CAMERA, StreamType.AUDIO));
        fixture.actor().handle(new SessionCommand.StartSession("start-1"));

        List<SessionText> examinerTexts =
                fixture.textStore().bySession(fixture.session().id(), "examiner");
        assertThat(examinerTexts).hasSize(1);
        assertThat(examinerTexts.get(0).speakerRef()).isEqualTo(
                fixture.task().interview().questions().get(0).questionId());
        assertThat(examinerTexts.get(0).text()).isEqualTo("请谈谈荷塘月色的写作背景。");
        assertThat(examinerTexts.get(0).kind()).isEqualTo("question");
    }

    // ---------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------

    private ActorFixture freeFixture(java.util.function.Supplier<FakeContentClient> clientFactory) {
        return fixture(TaskMode.FREE, clientFactory.get());
    }

    private ActorFixture interviewFixture(
            java.util.function.Supplier<FakeContentClient> clientFactory) {
        return fixture(TaskMode.INTERVIEW, clientFactory.get());
    }

    private ActorFixture fixture(TaskMode mode, FakeContentClient client) {
        return fixture(mode, client, () -> NOW);
    }

    private ActorFixture fixture(TaskMode mode, FakeContentClient client, ClockProvider clock) {
        UUID sessionUuid = UUID.randomUUID();
        Session session = Session.createPreparing(
                new SessionId(sessionUuid), SessionType.TASK, new TaskId(TASK_ID),
                new CourseId(COURSE_ID), new AccountId(STUDENT_ACCOUNT_ID), mode, 1, NOW);
        TaskSnapshot taskSnapshot = taskSnapshot(mode);
        VirtualStudentDynamics dynamics = new VirtualStudentDynamics(new java.util.Random(7));
        dynamics.initialize(taskSnapshot.virtualStudents().stream()
                .map(SessionActorTest::toConfig)
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

    private static int indexOfType(ActorFixture fixture, String type) {
        List<SessionEvent> events = fixture.context().after(0);
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).type().equals(type)) {
                return i;
            }
        }
        return -1;
    }

    private static Set<String> collectKeys(Object value) {
        Set<String> keys = new HashSet<>();
        collectKeys(value, keys);
        return keys;
    }

    private static void collectKeys(Object value, Set<String> keys) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                keys.add(String.valueOf(entry.getKey()));
                collectKeys(entry.getValue(), keys);
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                collectKeys(element, keys);
            }
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                keys.add(component.getName());
                try {
                    collectKeys(component.getAccessor().invoke(value), keys);
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(
                            "无法读取 record 组件 " + component.getName(), exception);
                }
            }
        }
    }

    private static Set<String> collectValues(Object value) {
        Set<String> values = new HashSet<>();
        collectValues(value, values);
        return values;
    }

    private static void collectValues(Object value, Set<String> values) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) {
                collectValues(nested, values);
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                collectValues(element, values);
            }
        } else if (value instanceof String text) {
            values.add(text);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    collectValues(component.getAccessor().invoke(value), values);
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(
                            "无法读取 record 组件 " + component.getName(), exception);
                }
            }
        }
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
