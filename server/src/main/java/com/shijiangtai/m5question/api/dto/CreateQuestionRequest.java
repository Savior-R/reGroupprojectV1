package com.shijiangtai.m5question.api.dto;

import java.util.UUID;

/** {@code POST /api/v1/question-bank/questions}. */
public record CreateQuestionRequest(UUID categoryId, String stem, String answerPoints) {
}
