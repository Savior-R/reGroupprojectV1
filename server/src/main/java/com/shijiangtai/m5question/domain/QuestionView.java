package com.shijiangtai.m5question.domain;

import java.time.Instant;
import java.util.UUID;

/** Read model returned by the question APIs and by M3 selection (04 §7). */
public record QuestionView(
        UUID questionId,
        UUID categoryId,
        String categoryName,
        String stem,
        String answerPoints,
        String status,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    public static QuestionView of(Question question, String categoryName) {
        return new QuestionView(
                question.id(),
                question.categoryId(),
                categoryName,
                question.stem(),
                question.answerPoints(),
                question.status().wireValue(),
                question.createdBy(),
                question.updatedBy(),
                question.createdAt(),
                question.updatedAt());
    }

    public boolean enabled() {
        return "active".equals(status);
    }
}
