package com.shijiangtai.m3classroom.domain.classroom;

import java.util.Objects;

/** One classified teacher-utterance intent with a 0—1 confidence. */
public record TeacherIntentHit(TeacherIntent intent, double confidence) {

    public TeacherIntentHit {
        Objects.requireNonNull(intent, "intent");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0, 1]");
        }
    }
}
