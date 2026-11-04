package com.shijiangtai.m3classroom.domain.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.domain.classroom.ClassroomEvent;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot.QuestionSnapshot;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class InterviewStateMachineTest {

    private static final Instant START = Instant.parse("2026-11-04T00:00:00Z");
    private static final int TOTAL_SECONDS = 300;

    private static final UUID CATEGORY_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID Q1_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID Q2_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ANSWER_1 = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");
    private static final UUID ANSWER_2 = UUID.fromString("b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2");
    private static final UUID ANSWER_3 = UUID.fromString("c3c3c3c3-c3c3-c3c3-c3c3-c3c3c3c3c3c3");

    private final InterviewStateMachine machine =
            new InterviewStateMachine(answerIds(ANSWER_1, ANSWER_2, ANSWER_3));

    @Test
    void startShowsFirstQuestionAndStartsAnswer() {
        List<ClassroomEvent> events = machine.start(questions(), START, TOTAL_SECONDS);

        assertThat(types(events))
                .containsExactly("interview.question_shown", "interview.answer_started");
        assertThat(events.get(0).payload())
                .containsEntry("question_id", Q1_ID)
                .containsEntry("order", 1)
                .containsEntry("total", 2)
                .containsEntry("text", "请介绍一下你自己")
                .hasSize(4);
        assertThat(events.get(1).payload())
                .containsEntry("question_id", Q1_ID)
                .containsEntry("answer_id", ANSWER_1)
                .hasSize(2);

        InterviewSnapshot snapshot = machine.snapshot(START);
        assertThat(snapshot.current())
                .isEqualTo(new InterviewQuestionState(Q1_ID, 1, 2, "请介绍一下你自己"));
        assertThat(snapshot.answerId()).isEqualTo(ANSWER_1);
        assertThat(snapshot.ended()).isFalse();
    }

    @Test
    void nextQuestionEndsPreviousThenShowsNext() {
        machine.start(questions(), START, TOTAL_SECONDS);

        List<ClassroomEvent> events = machine.nextQuestion(START.plusSeconds(20));

        assertThat(types(events)).containsExactly(
                "interview.answer_ended",
                "interview.next_question",
                "interview.question_shown",
                "interview.answer_started");
        assertThat(events.get(0).payload())
                .containsEntry("question_id", Q1_ID)
                .containsEntry("answer_id", ANSWER_1)
                .hasSize(2);
        assertThat(events.get(1).payload())
                .containsEntry("question_id", Q2_ID)
                .containsEntry("order", 2)
                .hasSize(2);
        assertThat(events.get(2).payload())
                .containsEntry("question_id", Q2_ID)
                .containsEntry("order", 2)
                .containsEntry("total", 2)
                .containsEntry("text", "你如何解决团队冲突")
                .hasSize(4);
        assertThat(events.get(3).payload())
                .containsEntry("question_id", Q2_ID)
                .containsEntry("answer_id", ANSWER_2)
                .hasSize(2);

        InterviewSnapshot snapshot = machine.snapshot(START.plusSeconds(20));
        assertThat(snapshot.current())
                .isEqualTo(new InterviewQuestionState(Q2_ID, 2, 2, "你如何解决团队冲突"));
        assertThat(snapshot.answerId()).isEqualTo(ANSWER_2);
        assertThat(snapshot.ended()).isFalse();
    }

    @Test
    void nextOnLastQuestionFails() {
        machine.start(questions(), START, TOTAL_SECONDS);
        Instant at = START.plusSeconds(20);
        machine.nextQuestion(at);
        InterviewSnapshot before = machine.snapshot(at);

        assertThatThrownBy(() -> machine.nextQuestion(at))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.INTERVIEW_NO_MORE_QUESTIONS));

        assertThat(machine.snapshot(at)).isEqualTo(before);
        assertThat(machine.state()).isEqualTo(InterviewState.PRESENTING);
    }

    @Test
    void endEmitsAnswerEndedAndInterviewEnded() {
        machine.start(questions(), START, TOTAL_SECONDS);

        List<ClassroomEvent> events = machine.end(START.plusSeconds(40), "manual");

        assertThat(types(events)).containsExactly(
                "interview.answer_ended", "interview.ended");
        assertThat(events.get(0).payload())
                .containsEntry("question_id", Q1_ID)
                .containsEntry("answer_id", ANSWER_1)
                .hasSize(2);
        assertThat(events.get(1).payload()).isEmpty();

        InterviewSnapshot snapshot = machine.snapshot(START.plusSeconds(40));
        assertThat(snapshot.ended()).isTrue();
        assertThat(snapshot.remainingMs()).isZero();
        assertThat(machine.state()).isEqualTo(InterviewState.ENDED);
        assertThat(machine.endReason()).isEqualTo("manual");

        assertThatThrownBy(() -> machine.end(START.plusSeconds(41), "manual"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SESSION_STATE_INVALID));
    }

    @Test
    void timeoutEmitsSameOrderWithReasonTimeout() {
        machine.start(questions(), START, TOTAL_SECONDS);

        List<ClassroomEvent> events = machine.onTimeout(START.plusSeconds(TOTAL_SECONDS));

        assertThat(types(events)).containsExactly(
                "interview.answer_ended", "interview.ended");
        assertThat(events.get(0).payload())
                .containsEntry("question_id", Q1_ID)
                .containsEntry("answer_id", ANSWER_1)
                .hasSize(2);
        assertThat(events.get(1).payload()).isEmpty();
        assertThat(machine.state()).isEqualTo(InterviewState.ENDED);
        assertThat(machine.endReason()).isEqualTo("timeout");

        InterviewSnapshot snapshot = machine.snapshot(START.plusSeconds(TOTAL_SECONDS));
        assertThat(snapshot.ended()).isTrue();
        assertThat(snapshot.remainingMs()).isZero();
    }

    @Test
    void snapshotExposesCurrentQuestionAndRemainingTime() {
        machine.start(questions(), START, TOTAL_SECONDS);

        InterviewSnapshot snapshot = machine.snapshot(START.plusSeconds(45));

        assertThat(snapshot.current())
                .isEqualTo(new InterviewQuestionState(Q1_ID, 1, 2, "请介绍一下你自己"));
        assertThat(snapshot.answerId()).isEqualTo(ANSWER_1);
        assertThat(snapshot.remainingMs()).isEqualTo(255_000L);
        assertThat(snapshot.ended()).isFalse();
    }

    @Test
    void snapshotBeforeStartIsEmpty() {
        InterviewSnapshot snapshot = machine.snapshot(START);

        assertThat(snapshot.current()).isNull();
        assertThat(snapshot.answerId()).isNull();
        assertThat(snapshot.remainingMs()).isZero();
        assertThat(snapshot.ended()).isFalse();
    }

    @Test
    void emptyQuestionListFailsAtStart() {
        assertThatThrownBy(() -> machine.start(List.of(), START, TOTAL_SECONDS))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SESSION_STATE_INVALID));

        assertThat(machine.state()).isEqualTo(InterviewState.NOT_STARTED);
        assertThat(machine.snapshot(START).current()).isNull();
        assertThat(machine.snapshot(START).answerId()).isNull();
    }

    @Test
    void commandsBeforeStartAreRejectedWithoutStateChange() {
        assertThatThrownBy(() -> machine.nextQuestion(START))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SESSION_STATE_INVALID));
        assertThatThrownBy(() -> machine.end(START, "manual"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SESSION_STATE_INVALID));
        assertThatThrownBy(() -> machine.onTimeout(START))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SESSION_STATE_INVALID));

        assertThat(machine.state()).isEqualTo(InterviewState.NOT_STARTED);
        assertThat(machine.snapshot(START).current()).isNull();
    }

    @Test
    void startTwiceIsRejected() {
        machine.start(questions(), START, TOTAL_SECONDS);

        assertThatThrownBy(() -> machine.start(questions(), START, TOTAL_SECONDS))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SESSION_STATE_INVALID));

        assertThat(machine.snapshot(START).answerId()).isEqualTo(ANSWER_1);
        assertThat(machine.state()).isEqualTo(InterviewState.PRESENTING);
    }

    @Test
    void answerIdsComeFromTheInjectedSupplier() {
        machine.start(questions(), START, TOTAL_SECONDS);
        List<ClassroomEvent> firstNext = machine.nextQuestion(START.plusSeconds(10));

        assertThat(firstNext.get(3).payload()).containsEntry("answer_id", ANSWER_2);

        assertThatThrownBy(() -> machine.nextQuestion(START.plusSeconds(20)))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.INTERVIEW_NO_MORE_QUESTIONS));
        assertThat(machine.snapshot(START.plusSeconds(20)).answerId()).isEqualTo(ANSWER_2);
        assertThat(machine.state()).isEqualTo(InterviewState.PRESENTING);
    }

    private static List<QuestionSnapshot> questions() {
        return List.of(
                new QuestionSnapshot(Q1_ID, 1, "请介绍一下你自己", "自我介绍要点", CATEGORY_ID, "自我介绍"),
                new QuestionSnapshot(Q2_ID, 2, "你如何解决团队冲突", "冲突处理要点", CATEGORY_ID, "团队协作"));
    }

    private static List<String> types(List<ClassroomEvent> events) {
        return events.stream().map(ClassroomEvent::type).toList();
    }

    private static Supplier<UUID> answerIds(UUID... ids) {
        Iterator<UUID> iterator = List.of(ids).iterator();
        return iterator::next;
    }
}
