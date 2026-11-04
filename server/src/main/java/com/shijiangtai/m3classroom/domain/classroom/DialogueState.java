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
 * <p>A dialogue starts at round 1 and may contain at most {@value #MAX_ROUNDS} rounds. A
 * follow-up is only possible while {@link #canFollowUp()} is true, and its probability is
 * {@code question_rate * 0.5}. A student can be in only one dialogue at a time; the caller keeps
 * the single {@link #studentId()} binding.
 */
public final class DialogueState {

    public static final int MAX_ROUNDS = 3;

    private static final double FOLLOW_UP_RATE_FACTOR = 0.5;

    private final UUID dialogueId;
    private final UUID studentId;
    private int round = 1;

    public DialogueState(UUID dialogueId, UUID studentId) {
        this.dialogueId = Objects.requireNonNull(dialogueId, "dialogueId");
        this.studentId = Objects.requireNonNull(studentId, "studentId");
    }

    public UUID dialogueId() {
        return dialogueId;
    }

    public UUID studentId() {
        return studentId;
    }

    /** Current round, 1-based; the initial question/answer is round 1. */
    public int round() {
        return round;
    }

    /** Whether another follow-up round is allowed; false once {@link #MAX_ROUNDS} is reached. */
    public boolean canFollowUp() {
        return round < MAX_ROUNDS;
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

    /** {@code p_follow_up = question_rate * 0.5} (05A §5.5). */
    public static double followUpProbability(double questionRate) {
        return questionRate * FOLLOW_UP_RATE_FACTOR;
    }

    /**
     * Draws the follow-up decision for the current round. Returns {@code false} at the round
     * limit without consuming a random draw.
     */
    public boolean shouldFollowUp(double questionRate, RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        return canFollowUp() && random.nextDouble() < followUpProbability(questionRate);
    }
}
