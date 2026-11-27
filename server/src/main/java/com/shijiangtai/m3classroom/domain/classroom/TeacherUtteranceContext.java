package com.shijiangtai.m3classroom.domain.classroom;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * State snapshot supplied to the teacher-utterance classifier.
 *
 * <p>Keeping the state out of the classifier lets the same utterance be interpreted differently
 * by the Java resolver depending on whether the classroom is lecturing, waiting for a hand or
 * already in a dialogue.
 */
public record TeacherUtteranceContext(
        String text,
        String macroState,
        String qaPhase,
        String dialogueInitiator,
        UUID currentStudentId,
        List<CandidateStudent> candidates) {

    public TeacherUtteranceContext {
        Objects.requireNonNull(text, "text");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public record CandidateStudent(UUID id, String name) {
        public CandidateStudent {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }
}
