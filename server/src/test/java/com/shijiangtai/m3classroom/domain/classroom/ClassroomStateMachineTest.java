package com.shijiangtai.m3classroom.domain.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassroomStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final UUID STUDENT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Test
    void askClassEntersWaitingHands() {
        ClassroomStateMachine machine = started();

        List<ClassroomEvent> events = machine.askClass(NOW);

        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.QA);
        assertThat(machine.qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        assertThat(machine.intent()).isEqualTo("answer");
        assertThat(machine.handWindowStartedAtMs()).isEqualTo(NOW.toEpochMilli());

        assertThat(types(events))
                .containsExactly("classroom.question_asked", "classroom.state_changed");
        assertThat(event(events, "classroom.question_asked").payload())
                .containsEntry("intent", "answer")
                .containsEntry("source", "manual")
                .hasSize(2);
        assertThat(event(events, "classroom.state_changed").payload())
                .containsEntry("from", "lecturing")
                .containsEntry("to", "qa")
                .containsEntry("qa_phase", "waiting_hands")
                .containsEntry("trigger", "manual")
                .hasSize(4);
    }

    @Test
    void askStudentQuestionUsesStudentQuestionIntent() {
        ClassroomStateMachine machine = started();

        List<ClassroomEvent> events = machine.askStudentQuestion(NOW);

        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.QA);
        assertThat(machine.qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        assertThat(machine.intent()).isEqualTo("student_question");
        assertThat(event(events, "classroom.question_asked").payload())
                .containsEntry("intent", "student_question")
                .containsEntry("source", "manual");
        assertThat(event(events, "classroom.state_changed").payload())
                .containsEntry("to", "qa")
                .containsEntry("qa_phase", "waiting_hands")
                .containsEntry("trigger", "manual");
    }

    @Test
    void callOnInLecturingIsRejected() {
        ClassroomStateMachine machine = started();

        assertStateInvalid(() -> machine.callOn(STUDENT_ID, NOW));

        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.LECTURING);
        assertThat(machine.qaPhase()).isNull();
        assertThat(machine.intent()).isNull();
        assertThat(machine.handWindowStartedAtMs()).isZero();
    }

    @Test
    void callOnMovesToStudentAnsweringOrTeacherAnswering() {
        ClassroomStateMachine answerMachine = started();
        answerMachine.askClass(NOW);

        List<ClassroomEvent> answerEvents = answerMachine.callOn(STUDENT_ID, NOW.plusSeconds(1));

        assertThat(answerMachine.qaPhase()).isEqualTo(QaPhase.STUDENT_ANSWERING);
        assertThat(answerMachine.macroState()).isEqualTo(ClassroomMacroState.QA);
        assertThat(answerMachine.handWindowStartedAtMs()).isZero();
        assertThat(types(answerEvents)).containsExactly("classroom.state_changed");
        assertThat(event(answerEvents, "classroom.state_changed").payload())
                .containsEntry("from", "qa")
                .containsEntry("to", "qa")
                .containsEntry("qa_phase", "student_answering")
                .containsEntry("trigger", "call_on");

        ClassroomStateMachine questionMachine = started();
        questionMachine.askStudentQuestion(NOW);

        List<ClassroomEvent> questionEvents =
                questionMachine.callOn(STUDENT_ID, NOW.plusSeconds(1));

        assertThat(questionMachine.qaPhase()).isEqualTo(QaPhase.TEACHER_ANSWERING);
        assertThat(event(questionEvents, "classroom.state_changed").payload())
                .containsEntry("qa_phase", "teacher_answering")
                .containsEntry("trigger", "call_on");
    }

    @Test
    void answerFinishedMovesToDialogueWhenFollowUp() {
        ClassroomStateMachine machine = started();
        machine.askClass(NOW);
        machine.callOn(STUDENT_ID, NOW.plusSeconds(1));

        List<ClassroomEvent> events =
                machine.answerFinished(STUDENT_ID, true, NOW.plusSeconds(20));

        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.QA);
        assertThat(machine.qaPhase()).isEqualTo(QaPhase.DIALOGUE);
        assertThat(types(events)).containsExactly("classroom.state_changed");
        assertThat(event(events, "classroom.state_changed").payload())
                .containsEntry("from", "qa")
                .containsEntry("to", "qa")
                .containsEntry("qa_phase", "dialogue")
                .containsEntry("trigger", "follow_up");
    }

    @Test
    void answerFinishedReturnsToWaitingHandsWithoutFollowUp() {
        ClassroomStateMachine machine = started();
        machine.askClass(NOW);
        machine.callOn(STUDENT_ID, NOW.plusSeconds(1));

        Instant finishedAt = NOW.plusSeconds(20);
        List<ClassroomEvent> events = machine.answerFinished(STUDENT_ID, false, finishedAt);

        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.QA);
        assertThat(machine.qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        assertThat(machine.handWindowStartedAtMs()).isEqualTo(finishedAt.toEpochMilli());
        assertThat(types(events)).containsExactly("classroom.state_changed");
        assertThat(event(events, "classroom.state_changed").payload())
                .containsEntry("qa_phase", "waiting_hands")
                .containsEntry("trigger", "answer_finished");
    }

    @Test
    void endDialogueReturnsToWaitingHands() {
        ClassroomStateMachine machine = started();
        machine.askClass(NOW);
        machine.callOn(STUDENT_ID, NOW.plusSeconds(1));
        machine.answerFinished(STUDENT_ID, true, NOW.plusSeconds(20));

        Instant endedAt = NOW.plusSeconds(40);
        List<ClassroomEvent> events = machine.endDialogue(endedAt);

        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.QA);
        assertThat(machine.qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        assertThat(machine.handWindowStartedAtMs()).isEqualTo(endedAt.toEpochMilli());
        assertThat(types(events)).containsExactly("classroom.state_changed");
        assertThat(event(events, "classroom.state_changed").payload())
                .containsEntry("from", "qa")
                .containsEntry("to", "qa")
                .containsEntry("qa_phase", "waiting_hands")
                .containsEntry("trigger", "end_dialogue");
    }

    @Test
    void endQaReturnsToLecturing() {
        ClassroomStateMachine machine = started();
        machine.askClass(NOW);

        List<ClassroomEvent> events = machine.endQa("manual", NOW.plusSeconds(15));

        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.LECTURING);
        assertThat(machine.qaPhase()).isNull();
        assertThat(machine.intent()).isNull();
        assertThat(machine.handWindowStartedAtMs()).isZero();

        assertThat(types(events))
                .containsExactly("classroom.qa_ended", "classroom.state_changed");
        assertThat(event(events, "classroom.qa_ended").payload())
                .containsEntry("reason", "manual")
                .hasSize(1);
        assertThat(event(events, "classroom.state_changed").payload())
                .containsEntry("from", "qa")
                .containsEntry("to", "lecturing")
                .containsEntry("qa_phase", null)
                .containsEntry("trigger", "end_qa");
    }

    @Test
    void handWindowTimeoutReturnsToLecturing() {
        ClassroomStateMachine machine = started();
        machine.askClass(NOW);

        List<ClassroomEvent> events = machine.handWindowTimeout(NOW.plusSeconds(30));

        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.LECTURING);
        assertThat(machine.qaPhase()).isNull();
        assertThat(machine.intent()).isNull();
        assertThat(machine.handWindowStartedAtMs()).isZero();

        assertThat(types(events))
                .containsExactly("classroom.hand_window_timeout", "classroom.state_changed");
        assertThat(event(events, "classroom.hand_window_timeout").payload())
                .containsEntry("hand_raise_ids", List.of())
                .hasSize(1);
        assertThat(event(events, "classroom.state_changed").payload())
                .containsEntry("from", "qa")
                .containsEntry("to", "lecturing")
                .containsEntry("qa_phase", null)
                .containsEntry("trigger", "hand_window_timeout");
    }

    @Test
    void voiceHighConfidenceTriggersAndLowConfidenceSuggests() {
        ClassroomStateMachine lowConfidence = started();

        List<ClassroomEvent> suggested = lowConfidence.onTeacherUtterance(0.79, NOW);

        assertThat(lowConfidence.macroState()).isEqualTo(ClassroomMacroState.LECTURING);
        assertThat(lowConfidence.qaPhase()).isNull();
        assertThat(types(suggested)).containsExactly("classroom.question_suggested");
        assertThat(event(suggested, "classroom.question_suggested").payload())
                .containsEntry("confidence", 0.79)
                .hasSize(1);

        ClassroomStateMachine highConfidence = started();

        List<ClassroomEvent> triggered = highConfidence.onTeacherUtterance(0.8, NOW);

        assertThat(highConfidence.macroState()).isEqualTo(ClassroomMacroState.QA);
        assertThat(highConfidence.qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        assertThat(highConfidence.intent()).isEqualTo("answer");
        assertThat(types(triggered))
                .containsExactly("classroom.question_asked", "classroom.state_changed");
        assertThat(event(triggered, "classroom.question_asked").payload())
                .containsEntry("intent", "answer")
                .containsEntry("source", "voice");
        assertThat(event(triggered, "classroom.state_changed").payload())
                .containsEntry("from", "lecturing")
                .containsEntry("to", "qa")
                .containsEntry("qa_phase", "waiting_hands")
                .containsEntry("trigger", "voice");

        highConfidence.endQa("manual", NOW.plusSeconds(1));
        List<ClassroomEvent> stillCoolingDown =
                highConfidence.onTeacherUtterance(0.95, NOW.plusSeconds(9).plusMillis(999));
        assertThat(highConfidence.macroState()).isEqualTo(ClassroomMacroState.LECTURING);
        assertThat(types(stillCoolingDown)).containsExactly("classroom.question_suggested");

        List<ClassroomEvent> afterCooldown =
                highConfidence.onTeacherUtterance(0.95, NOW.plusSeconds(10));
        assertThat(highConfidence.macroState()).isEqualTo(ClassroomMacroState.QA);
        assertThat(types(afterCooldown))
                .containsExactly("classroom.question_asked", "classroom.state_changed");
        assertThat(event(afterCooldown, "classroom.state_changed").payload())
                .containsEntry("trigger", "voice");
    }

    @Test
    void lowConfidenceDoesNotStartVoiceCooldown() {
        ClassroomStateMachine machine = started();

        machine.onTeacherUtterance(0.4, NOW);
        List<ClassroomEvent> events = machine.onTeacherUtterance(0.9, NOW.plusMillis(1));

        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.QA);
        assertThat(types(events))
                .containsExactly("classroom.question_asked", "classroom.state_changed");
    }

    @Test
    void voiceDoesNotTriggerOutsideLecturing() {
        ClassroomStateMachine machine = started();
        machine.askClass(NOW);

        List<ClassroomEvent> events = machine.onTeacherUtterance(0.95, NOW.plusSeconds(1));

        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.QA);
        assertThat(machine.qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);
        assertThat(types(events)).containsExactly("classroom.question_suggested");
    }

    @Test
    void endSessionMovesToEnded() {
        ClassroomStateMachine machine = started();
        machine.askClass(NOW);

        List<ClassroomEvent> events = machine.endSession(NOW.plusSeconds(3));

        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.ENDED);
        assertThat(machine.qaPhase()).isNull();
        assertThat(machine.intent()).isNull();
        assertThat(machine.handWindowStartedAtMs()).isZero();
        assertThat(types(events)).containsExactly("classroom.state_changed");
        assertThat(event(events, "classroom.state_changed").payload())
                .containsEntry("from", "qa")
                .containsEntry("to", "ended")
                .containsEntry("qa_phase", null)
                .containsEntry("trigger", "end_session");

        assertStateInvalid(() -> machine.askClass(NOW.plusSeconds(4)));
        assertThat(machine.macroState()).isEqualTo(ClassroomMacroState.ENDED);
    }

    @Test
    void illegalTransitionsAreRejectedWithoutMutation() {
        ClassroomStateMachine lecturing = started();

        assertStateInvalid(() -> lecturing.answerFinished(STUDENT_ID, true, NOW));
        assertStateInvalid(() -> lecturing.endDialogue(NOW));
        assertStateInvalid(() -> lecturing.endQa("manual", NOW));
        assertStateInvalid(() -> lecturing.handWindowTimeout(NOW));
        assertThat(lecturing.macroState()).isEqualTo(ClassroomMacroState.LECTURING);
        assertThat(lecturing.qaPhase()).isNull();

        ClassroomStateMachine waitingHands = started();
        waitingHands.askClass(NOW);
        assertStateInvalid(() -> waitingHands.answerFinished(STUDENT_ID, true, NOW));
        assertStateInvalid(() -> waitingHands.endDialogue(NOW));
        assertStateInvalid(() -> waitingHands.askClass(NOW));
        assertThat(waitingHands.macroState()).isEqualTo(ClassroomMacroState.QA);
        assertThat(waitingHands.qaPhase()).isEqualTo(QaPhase.WAITING_HANDS);

        ClassroomStateMachine dialogue = started();
        dialogue.askClass(NOW);
        dialogue.callOn(STUDENT_ID, NOW.plusSeconds(1));
        dialogue.answerFinished(STUDENT_ID, true, NOW.plusSeconds(10));
        assertStateInvalid(() -> dialogue.handWindowTimeout(NOW));
        assertThat(dialogue.qaPhase()).isEqualTo(QaPhase.DIALOGUE);

        ClassroomStateMachine ended = started();
        ended.endSession(NOW);
        assertStateInvalid(() -> ended.endSession(NOW));
        assertStateInvalid(() -> ended.onTeacherUtterance(0.9, NOW));
        assertThat(ended.macroState()).isEqualTo(ClassroomMacroState.ENDED);
    }

    @Test
    void wireValuesMatchProtocol() {
        assertThat(ClassroomMacroState.LECTURING.wireValue()).isEqualTo("lecturing");
        assertThat(ClassroomMacroState.QA.wireValue()).isEqualTo("qa");
        assertThat(ClassroomMacroState.ENDED.wireValue()).isEqualTo("ended");
        for (ClassroomMacroState state : ClassroomMacroState.values()) {
            assertThat(ClassroomMacroState.fromWire(state.wireValue())).isEqualTo(state);
        }

        assertThat(QaPhase.WAITING_HANDS.wireValue()).isEqualTo("waiting_hands");
        assertThat(QaPhase.STUDENT_ANSWERING.wireValue()).isEqualTo("student_answering");
        assertThat(QaPhase.DIALOGUE.wireValue()).isEqualTo("dialogue");
        assertThat(QaPhase.TEACHER_ANSWERING.wireValue()).isEqualTo("teacher_answering");
        for (QaPhase phase : QaPhase.values()) {
            assertThat(QaPhase.fromWire(phase.wireValue())).isEqualTo(phase);
        }
    }

    @Test
    void unknownWireValuesAreRejected() {
        assertThatThrownBy(() -> ClassroomMacroState.fromWire("paused"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> QaPhase.fromWire("answering"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void classroomEventCopiesPayloadDefensively() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("reason", "manual");

        ClassroomEvent event = new ClassroomEvent("classroom.qa_ended", mutable);
        mutable.put("reason", "changed");

        assertThat(event.payload()).containsEntry("reason", "manual");
        assertThatThrownBy(() -> event.payload().put("reason", "mutated"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ClassroomStateMachine started() {
        ClassroomStateMachine machine = new ClassroomStateMachine();
        machine.start();
        return machine;
    }

    private static List<String> types(List<ClassroomEvent> events) {
        return events.stream().map(ClassroomEvent::type).toList();
    }

    private static ClassroomEvent event(List<ClassroomEvent> events, String type) {
        return events.stream()
                .filter(candidate -> candidate.type().equals(type))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("missing event " + type + " in " + types(events)));
    }

    private static void assertStateInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_STATE_INVALID));
    }
}
