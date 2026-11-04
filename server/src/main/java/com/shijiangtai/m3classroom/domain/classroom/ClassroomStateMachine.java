package com.shijiangtai.m3classroom.domain.classroom;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Classroom macro-state machine: {@code LECTURING -> QA -> LECTURING}, with
 * {@code ENDED} reachable from both non-terminal states.
 *
 * <p>Inside {@link ClassroomMacroState#QA} the phase is tracked with {@link QaPhase}:
 * <ul>
 *   <li>manual/voice question or "let students ask" enters {@code waiting_hands};</li>
 *   <li>calling on a student enters {@code student_answering} for an answer intent and
 *       {@code teacher_answering} for a student-question intent;</li>
 *   <li>a finished answer enters {@code dialogue} when followed up, otherwise returns to
 *       {@code waiting_hands};</li>
 *   <li>{@code end_dialogue} returns to {@code waiting_hands};</li>
 *   <li>{@code end_qa} / hand-window timeout return to {@code lecturing};</li>
 *   <li>{@code end_session} moves to {@code ended}.</li>
 * </ul>
 *
 * <p>Every illegal transition throws {@link DomainException} with
 * {@link ErrorCode#SESSION_STATE_INVALID} and leaves the machine unchanged. There is no
 * automatic QA/dialogue timeout; only {@link #handWindowTimeout(Instant)} closes a hand window.
 *
 * <p>Event order mirrors 05A: a domain fact (for example {@code classroom.question_asked} or
 * {@code classroom.hand_window_timeout}) precedes the corresponding
 * {@code classroom.state_changed}. State-change triggers are {@code manual}, {@code voice},
 * {@code call_on}, {@code follow_up}, {@code answer_finished}, {@code end_dialogue},
 * {@code end_qa}, {@code hand_window_timeout}, and {@code end_session}.
 */
public final class ClassroomStateMachine {

    public static final String INTENT_ANSWER = "answer";
    public static final String INTENT_STUDENT_QUESTION = "student_question";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_VOICE = "voice";

    private static final String STATE_CHANGED = "classroom.state_changed";
    private static final String QUESTION_ASKED = "classroom.question_asked";
    private static final String QUESTION_SUGGESTED = "classroom.question_suggested";
    private static final String QA_ENDED = "classroom.qa_ended";
    private static final String HAND_WINDOW_TIMEOUT = "classroom.hand_window_timeout";

    private static final double VOICE_CONFIDENCE_THRESHOLD = 0.8;
    private static final Duration VOICE_COOLDOWN = Duration.ofSeconds(10);

    private ClassroomMacroState macroState = ClassroomMacroState.LECTURING;
    private QaPhase qaPhase;
    private String intent;
    private Instant lastVoiceQuestionAt;
    private long handWindowStartedAtMs;

    /**
     * Marks the classroom as started. The session layer emits {@code session.started}; the
     * classroom state machine itself moves to {@code lecturing} without a
     * {@code classroom.state_changed} event.
     */
    public void start() {
        if (macroState == ClassroomMacroState.ENDED) {
            throw stateInvalid("start", "课堂已结束");
        }
        this.macroState = ClassroomMacroState.LECTURING;
        this.qaPhase = null;
        this.intent = null;
        this.lastVoiceQuestionAt = null;
        this.handWindowStartedAtMs = 0L;
    }

    /** Teacher asks the whole class; intent {@code answer}, source {@code manual}. */
    public List<ClassroomEvent> askClass(Instant now) {
        Objects.requireNonNull(now, "now");
        return enterWaitingHands(INTENT_ANSWER, SOURCE_MANUAL, "manual", now);
    }

    /** Teacher asks students to pose questions; intent {@code student_question}. */
    public List<ClassroomEvent> askStudentQuestion(Instant now) {
        Objects.requireNonNull(now, "now");
        return enterWaitingHands(INTENT_STUDENT_QUESTION, SOURCE_MANUAL, "manual", now);
    }

    /**
     * Handles a classified teacher utterance. A high-confidence question ({@code >= 0.8})
     * while lecturing and outside the 10 s voice cooldown starts a voice-triggered QA; every
     * other case only emits {@code classroom.question_suggested}. Low-confidence utterances do
     * not consume the cooldown.
     */
    public List<ClassroomEvent> onTeacherUtterance(double confidence, Instant now) {
        Objects.requireNonNull(now, "now");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "置信度必须在 0 到 1 之间",
                    Map.of("field", "confidence", "value", confidence));
        }
        if (macroState == ClassroomMacroState.ENDED) {
            throw stateInvalid("onTeacherUtterance", "课堂已结束");
        }
        boolean cooldownElapsed = lastVoiceQuestionAt == null
                || !now.isBefore(lastVoiceQuestionAt.plus(VOICE_COOLDOWN));
        if (macroState == ClassroomMacroState.LECTURING
                && confidence >= VOICE_CONFIDENCE_THRESHOLD
                && cooldownElapsed) {
            lastVoiceQuestionAt = now;
            return enterWaitingHands(INTENT_ANSWER, SOURCE_VOICE, "voice", now);
        }
        return List.of(new ClassroomEvent(QUESTION_SUGGESTED,
                Map.of("confidence", confidence)));
    }

    /**
     * Calls on a student from the hand window. Answer intent moves to
     * {@code student_answering}; student-question intent moves to {@code teacher_answering}.
     */
    public List<ClassroomEvent> callOn(UUID studentId, Instant now) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(now, "now");
        requireQaPhase("callOn", QaPhase.WAITING_HANDS);
        QaPhase target = INTENT_STUDENT_QUESTION.equals(intent)
                ? QaPhase.TEACHER_ANSWERING
                : QaPhase.STUDENT_ANSWERING;
        this.qaPhase = target;
        this.handWindowStartedAtMs = 0L;
        return List.of(stateChanged(
                ClassroomMacroState.QA, ClassroomMacroState.QA, target, "call_on"));
    }

    /**
     * Records a finished student answer. A follow-up continues into {@code dialogue};
     * otherwise the machine waits for another hand raise. Allowed while
     * {@code student_answering} or already in {@code dialogue}; a follow-up that keeps the
     * dialogue phase emits no state-change event.
     */
    public List<ClassroomEvent> answerFinished(UUID studentId, boolean followUp, Instant now) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(now, "now");
        if (macroState != ClassroomMacroState.QA
                || (qaPhase != QaPhase.STUDENT_ANSWERING && qaPhase != QaPhase.DIALOGUE)) {
            throw stateInvalid("answerFinished", "当前状态不允许该操作");
        }
        QaPhase target = followUp ? QaPhase.DIALOGUE : QaPhase.WAITING_HANDS;
        if (target == qaPhase) {
            return List.of();
        }
        this.qaPhase = target;
        this.handWindowStartedAtMs = target == QaPhase.WAITING_HANDS ? now.toEpochMilli() : 0L;
        return List.of(stateChanged(ClassroomMacroState.QA, ClassroomMacroState.QA, target,
                followUp ? "follow_up" : "answer_finished"));
    }

    /** Ends the current dialogue and waits for the next hand raise. */
    public List<ClassroomEvent> endDialogue(Instant now) {
        Objects.requireNonNull(now, "now");
        requireQaPhase("endDialogue", QaPhase.DIALOGUE);
        this.qaPhase = QaPhase.WAITING_HANDS;
        this.handWindowStartedAtMs = now.toEpochMilli();
        return List.of(stateChanged(ClassroomMacroState.QA, ClassroomMacroState.QA,
                QaPhase.WAITING_HANDS, "end_dialogue"));
    }

    /** Ends the whole QA block and returns to lecturing. */
    public List<ClassroomEvent> endQa(String reason, Instant now) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(now, "now");
        if (macroState != ClassroomMacroState.QA) {
            throw stateInvalid("endQa", "当前状态不允许该操作");
        }
        ClassroomMacroState from = macroState;
        this.macroState = ClassroomMacroState.LECTURING;
        this.qaPhase = null;
        this.intent = null;
        this.handWindowStartedAtMs = 0L;
        return List.of(
                new ClassroomEvent(QA_ENDED, Map.of("reason", reason)),
                stateChanged(from, ClassroomMacroState.LECTURING, null, "end_qa"));
    }

    /**
     * Closes the hand window without a called-on student and returns to lecturing. The
     * caller may supply the open hand-raise ids via the overload; the base signature emits
     * an empty {@code hand_raise_ids} list.
     */
    public List<ClassroomEvent> handWindowTimeout(Instant now) {
        return handWindowTimeout(now, List.of());
    }

    public List<ClassroomEvent> handWindowTimeout(Instant now, List<UUID> handRaiseIds) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(handRaiseIds, "handRaiseIds");
        requireQaPhase("handWindowTimeout", QaPhase.WAITING_HANDS);
        ClassroomMacroState from = macroState;
        this.macroState = ClassroomMacroState.LECTURING;
        this.qaPhase = null;
        this.intent = null;
        this.handWindowStartedAtMs = 0L;
        return List.of(
                new ClassroomEvent(HAND_WINDOW_TIMEOUT,
                        Map.of("hand_raise_ids", List.copyOf(handRaiseIds))),
                stateChanged(from, ClassroomMacroState.LECTURING, null, "hand_window_timeout"));
    }

    /** Ends the classroom; no further transition is allowed afterwards. */
    public List<ClassroomEvent> endSession(Instant now) {
        Objects.requireNonNull(now, "now");
        if (macroState == ClassroomMacroState.ENDED) {
            throw stateInvalid("endSession", "课堂已结束");
        }
        ClassroomMacroState from = macroState;
        this.macroState = ClassroomMacroState.ENDED;
        this.qaPhase = null;
        this.intent = null;
        this.handWindowStartedAtMs = 0L;
        return List.of(stateChanged(from, ClassroomMacroState.ENDED, null, "end_session"));
    }

    public ClassroomMacroState macroState() {
        return macroState;
    }

    /** Returns the current QA phase, or {@code null} outside {@link ClassroomMacroState#QA}. */
    public QaPhase qaPhase() {
        return qaPhase;
    }

    /** Epoch millis at which the current {@code waiting_hands} window started; 0 when inactive. */
    public long handWindowStartedAtMs() {
        return handWindowStartedAtMs;
    }

    /** Current question intent ({@code answer} or {@code student_question}), or null outside QA. */
    public String intent() {
        return intent;
    }

    private List<ClassroomEvent> enterWaitingHands(
            String questionIntent, String source, String trigger, Instant now) {
        if (macroState != ClassroomMacroState.LECTURING) {
            throw stateInvalid("ask", "当前状态不允许该操作");
        }
        ClassroomMacroState from = macroState;
        this.macroState = ClassroomMacroState.QA;
        this.qaPhase = QaPhase.WAITING_HANDS;
        this.intent = questionIntent;
        this.handWindowStartedAtMs = now.toEpochMilli();
        return List.of(
                new ClassroomEvent(QUESTION_ASKED,
                        Map.of("intent", questionIntent, "source", source)),
                stateChanged(from, ClassroomMacroState.QA, QaPhase.WAITING_HANDS, trigger));
    }

    private void requireQaPhase(String action, QaPhase expected) {
        if (macroState != ClassroomMacroState.QA || qaPhase != expected) {
            throw stateInvalid(action, "当前状态不允许该操作");
        }
    }

    private DomainException stateInvalid(String action, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action);
        details.put("state", macroState.wireValue());
        if (qaPhase != null) {
            details.put("qa_phase", qaPhase.wireValue());
        }
        return new DomainException(ErrorCode.SESSION_STATE_INVALID, message, details);
    }

    private static ClassroomEvent stateChanged(
            ClassroomMacroState from, ClassroomMacroState to, QaPhase phase, String trigger) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from.wireValue());
        payload.put("to", to.wireValue());
        payload.put("qa_phase", phase == null ? null : phase.wireValue());
        payload.put("trigger", trigger);
        return new ClassroomEvent(STATE_CHANGED, payload);
    }
}
