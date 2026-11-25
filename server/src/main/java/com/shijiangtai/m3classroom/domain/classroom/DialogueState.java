package com.shijiangtai.m3classroom.domain.classroom;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Runtime state of one teacher-student dialogue (05A §5.5).
 *
 * <p>A dialogue starts when the teacher calls on a student, in either direction:
 * <ul>
 *   <li>{@link Initiator#TEACHER}: the teacher asked, the student answered, and every later
 *       turn is driven by the teacher's follow-up questions or comments;</li>
 *   <li>{@link Initiator#STUDENT}: the student asked a question, the teacher answered, and the
 *       student decides whether to follow up.</li>
 * </ul>
 *
 * <p>{@link Phase} records who is expected to speak next. A dialogue contains at most
 * {@value #MAX_ROUNDS} rounds; a round is one teacher question plus one student answer (or one
 * student question plus one teacher answer in the student-initiated direction).
 */
public final class DialogueState {

    public static final int MAX_ROUNDS = 3;

    private static final double FOLLOW_UP_RATE_FACTOR = 0.5;

    private final UUID dialogueId;
    private final UUID studentId;
    private final Initiator initiator;
    private Phase phase = Phase.AWAITING_TEACHER;
    private int round = 1;
    private long awaitingSinceMs;

    public DialogueState(UUID dialogueId, UUID studentId) {
        this(dialogueId, studentId, Initiator.TEACHER);
    }

    public DialogueState(UUID dialogueId, UUID studentId, Initiator initiator) {
        this.dialogueId = Objects.requireNonNull(dialogueId, "dialogueId");
        this.studentId = Objects.requireNonNull(studentId, "studentId");
        this.initiator = Objects.requireNonNull(initiator, "initiator");
    }

    public UUID dialogueId() {
        return dialogueId;
    }

    public UUID studentId() {
        return studentId;
    }

    public Initiator initiator() {
        return initiator;
    }

    public Phase phase() {
        return phase;
    }

    public long awaitingSinceMs() {
        return awaitingSinceMs;
    }

    /** Current round, 1-based; the initial question/answer is round 1. */
    public int round() {
        return round;
    }

    /** Whether another round is allowed; false once {@link #MAX_ROUNDS} is reached. */
    public boolean canFollowUp() {
        return round < MAX_ROUNDS;
    }

    /** Marks the teacher as the expected next speaker. */
    public void awaitTeacher(long atMs) {
        this.phase = Phase.AWAITING_TEACHER;
        this.awaitingSinceMs = atMs;
    }

    /** Marks the student as the expected next speaker. */
    public void awaitStudent(long atMs) {
        this.phase = Phase.AWAITING_STUDENT;
        this.awaitingSinceMs = atMs;
    }

    /** Advances to the next round; illegal once {@link #canFollowUp()} is false. */
    public void nextRound() {
        if (!canFollowUp()) {
            throw new DomainException(ErrorCode.SESSION_STATE_INVALID,
                    "对话已达最大轮次",
                    Map.of("dialogue_id", dialogueId.toString(), "round", round));
        }
        round++;
    }

    /** {@code p_follow_up = question_rate * 0.5} for student-initiated dialogues (05A §5.5). */
    public static double followUpProbability(double questionRate) {
        return questionRate * FOLLOW_UP_RATE_FACTOR;
    }

    /**
     * Draws the follow-up decision for a student-initiated dialogue. Returns {@code false} at
     * the round limit without consuming a random draw.
     */
    public boolean shouldFollowUp(double questionRate, RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        return canFollowUp() && random.nextDouble() < followUpProbability(questionRate);
    }

    /** Who opened the dialogue and therefore who drives the follow-up rounds. */
    public enum Initiator {
        TEACHER("teacher"),
        STUDENT("student");

        private final String wireValue;

        Initiator(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    /** Who is expected to speak next. */
    public enum Phase {
        AWAITING_TEACHER("awaiting_teacher"),
        AWAITING_STUDENT("awaiting_student");

        private final String wireValue;

        Phase(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }
}
