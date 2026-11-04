package com.shijiangtai.m3classroom.domain.interview;

import java.util.Objects;
import java.util.UUID;

/**
 * The question currently on screen during a structured interview.
 *
 * @param questionId question bank id from the published task snapshot
 * @param order      1-based question order as configured in the task snapshot
 * @param total      total number of questions in the interview
 * @param text       question stem shown to the candidate
 */
public record InterviewQuestionState(UUID questionId, int order, int total, String text) {

    public InterviewQuestionState {
        Objects.requireNonNull(questionId, "questionId");
        Objects.requireNonNull(text, "text");
    }
}
