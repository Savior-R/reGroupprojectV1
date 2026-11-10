package com.shijiangtai.m5question.application.command;

import java.util.UUID;

/** Create a question in one of the caller's categories. */
public record CreateQuestionCommand(UUID categoryId, String stem, String answerPoints) {
}
