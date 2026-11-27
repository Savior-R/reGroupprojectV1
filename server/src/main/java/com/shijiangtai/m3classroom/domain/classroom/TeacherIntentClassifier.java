package com.shijiangtai.m3classroom.domain.classroom;

/**
 * Port for teacher-utterance intent classification.
 *
 * <p>The first implementation is the deterministic {@link RuleBasedTeacherIntentClassifier};
 * a locally trained model can replace it later without changing the dialogue state machine.
 */
@FunctionalInterface
public interface TeacherIntentClassifier {

    TeacherUtteranceIntent classify(TeacherUtteranceContext context);

    /**
     * Whether this classifier may open a voice-QA hand window while lecturing. Rules and
     * shadow deployments return {@code false} so the existing Python question classifier remains
     * the only voice-question entry point; an active local model returns {@code true}.
     */
    default boolean drivesLecturingQuestions() {
        return false;
    }
}
