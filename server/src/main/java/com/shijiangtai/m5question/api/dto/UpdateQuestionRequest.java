package com.shijiangtai.m5question.api.dto;

import java.util.UUID;

/** {@code PATCH /api/v1/question-bank/questions/{id}}. */
public record UpdateQuestionRequest(
        UUID categoryId, String stem, String answerPoints, String status) {
}
