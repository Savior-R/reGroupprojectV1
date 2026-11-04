package com.shijiangtai.m3classroom.domain.interview;

import com.shijiangtai.m3classroom.domain.classroom.ClassroomEvent;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot.QuestionSnapshot;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Structured-interview state machine (05A §5.6, implementation/04 §4.4).
 *
 * <p>Questions come from the published task snapshot. Showing a question immediately opens its
 * answer window, so every {@code interview.question_shown} is followed by
 * {@code interview.answer_started} carrying the {@code answer_id} that all ASR final segments of
 * that question share. Moving to the next question first closes the current answer, then emits
 * {@code interview.next_question} for the new question before showing it. Ending or timing out
 * closes the current answer and emits {@code interview.ended} with an empty payload; the session
 * layer emits {@code session.ended} afterwards.
 *
 * <p>Asking for the next question on the last question fails with
 * {@link ErrorCode#INTERVIEW_NO_MORE_QUESTIONS} without changing state. Any operation outside
 * {@link InterviewState#PRESENTING} fails with {@link ErrorCode#SESSION_STATE_INVALID} without
 * changing state.
 */
public final class InterviewStateMachine {

    public static final String REASON_MANUAL = "manual";
    public static final String REASON_TIMEOUT = "timeout";

    private static final String QUESTION_SHOWN = "interview.question_shown";
    private static final String ANSWER_STARTED = "interview.answer_started";
    private static final String ANSWER_ENDED = "interview.answer_ended";
    private static final String NEXT_QUESTION = "interview.next_question";
    private static final String INTERVIEW_ENDED = "interview.ended";

    private final Supplier<UUID> answerIds;

    private InterviewState state = InterviewState.NOT_STARTED;
    private List<QuestionSnapshot> questions = List.of();
    private int currentIndex = -1;
    private UUID answerId;
    private Instant startedAt;
    private int totalDurationSeconds;
    private String endReason;

    public InterviewStateMachine(Supplier<UUID> answerIds) {
        this.answerIds = Objects.requireNonNull(answerIds, "answerIds");
    }

    /**
     * Starts the interview and shows the first question. Returns
     * {@code question_shown} followed by {@code answer_started}. An empty question list is
     * rejected with {@link ErrorCode#SESSION_STATE_INVALID}.
     */
    public List<ClassroomEvent> start(
            List<QuestionSnapshot> questions, Instant startedAt, int totalDurationSeconds) {
        Objects.requireNonNull(questions, "questions");
        Objects.requireNonNull(startedAt, "startedAt");
        if (state != InterviewState.NOT_STARTED) {
            throw stateInvalid("start", "面试已开始");
        }
        if (questions.isEmpty()) {
            throw stateInvalid("start", "面试题目为空");
        }
        if (totalDurationSeconds <= 0) {
            throw new IllegalArgumentException("totalDurationSeconds must be positive");
        }
        this.questions = List.copyOf(questions);
        this.startedAt = startedAt;
        this.totalDurationSeconds = totalDurationSeconds;
        this.currentIndex = 0;
        this.answerId = nextAnswerId();
        this.endReason = null;
        this.state = InterviewState.PRESENTING;

        QuestionSnapshot first = currentQuestion();
        return List.of(questionShown(first), answerEvent(ANSWER_STARTED, first, answerId));
    }

    /**
     * Closes the current answer and opens the next question: {@code answer_ended},
     * {@code next_question}, {@code question_shown}, {@code answer_started}. On the last
     * question it throws {@link ErrorCode#INTERVIEW_NO_MORE_QUESTIONS} without changing state.
     */
    public List<ClassroomEvent> nextQuestion(Instant now) {
        Objects.requireNonNull(now, "now");
        requirePresenting("nextQuestion");
        if (currentIndex >= questions.size() - 1) {
            throw new DomainException(ErrorCode.INTERVIEW_NO_MORE_QUESTIONS, "已是最后一题");
        }

        QuestionSnapshot ended = currentQuestion();
        UUID endedAnswerId = answerId;
        currentIndex++;
        QuestionSnapshot next = currentQuestion();
        UUID nextAnswerId = nextAnswerId();
        this.answerId = nextAnswerId;

        return List.of(
                answerEvent(ANSWER_ENDED, ended, endedAnswerId),
                nextQuestionEvent(next),
                questionShown(next),
                answerEvent(ANSWER_STARTED, next, nextAnswerId));
    }

    /**
     * Ends the interview manually or on timeout: {@code answer_ended} then
     * {@code interview.ended} with an empty payload. The session layer is responsible for the
     * following {@code session.ended} event and its reason.
     */
    public List<ClassroomEvent> end(Instant now, String reason) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(reason, "reason");
        requirePresenting("end");

        QuestionSnapshot ended = currentQuestion();
        UUID endedAnswerId = answerId;
        this.state = InterviewState.ENDED;
        this.endReason = reason;

        return List.of(
                answerEvent(ANSWER_ENDED, ended, endedAnswerId),
                new ClassroomEvent(INTERVIEW_ENDED, Map.of()));
    }

    /** Called when the total interview duration elapses; same event order as {@link #end}. */
    public List<ClassroomEvent> onTimeout(Instant now) {
        return end(now, REASON_TIMEOUT);
    }

    /**
     * Current interview state for the session snapshot. Before start the current question and
     * answer id are {@code null}; after ending the remaining time is zero and {@code ended} is
     * true.
     */
    public InterviewSnapshot snapshot(Instant now) {
        Objects.requireNonNull(now, "now");
        if (state == InterviewState.NOT_STARTED) {
            return new InterviewSnapshot(null, null, 0L, false);
        }
        long remainingMs = 0L;
        if (state == InterviewState.PRESENTING) {
            long totalMs = totalDurationSeconds * 1000L;
            long elapsedMs = Duration.between(startedAt, now).toMillis();
            remainingMs = Math.max(0L, Math.min(totalMs, totalMs - elapsedMs));
        }
        return new InterviewSnapshot(
                questionState(currentQuestion()),
                answerId,
                remainingMs,
                state == InterviewState.ENDED);
    }

    public InterviewState state() {
        return state;
    }

    /** Reason passed to {@link #end} (or {@code timeout} from {@link #onTimeout}), else null. */
    public String endReason() {
        return endReason;
    }

    private QuestionSnapshot currentQuestion() {
        return questions.get(currentIndex);
    }

    private InterviewQuestionState questionState(QuestionSnapshot question) {
        return new InterviewQuestionState(
                question.questionId(), question.order(), questions.size(), question.stem());
    }

    private ClassroomEvent questionShown(QuestionSnapshot question) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question_id", question.questionId());
        payload.put("order", question.order());
        payload.put("total", questions.size());
        payload.put("text", question.stem());
        return new ClassroomEvent(QUESTION_SHOWN, payload);
    }

    private static ClassroomEvent nextQuestionEvent(QuestionSnapshot question) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question_id", question.questionId());
        payload.put("order", question.order());
        return new ClassroomEvent(NEXT_QUESTION, payload);
    }

    private static ClassroomEvent answerEvent(
            String type, QuestionSnapshot question, UUID answerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question_id", question.questionId());
        payload.put("answer_id", answerId);
        return new ClassroomEvent(type, payload);
    }

    private UUID nextAnswerId() {
        return Objects.requireNonNull(answerIds.get(), "answerId");
    }

    private void requirePresenting(String action) {
        if (state != InterviewState.PRESENTING) {
            throw stateInvalid(action, "当前状态不允许该操作");
        }
    }

    private DomainException stateInvalid(String action, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action);
        details.put("state", state.wireValue());
        return new DomainException(ErrorCode.SESSION_STATE_INVALID, message, details);
    }
}
