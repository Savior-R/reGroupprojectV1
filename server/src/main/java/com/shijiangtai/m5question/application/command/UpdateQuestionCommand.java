package com.shijiangtai.m5question.application.command;

import java.util.UUID;

/** Patch a question; null fields are left unchanged. */
public record UpdateQuestionCommand(
        UUID categoryId, String stem, String answerPoints, String status) {
}
