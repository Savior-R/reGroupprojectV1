package com.shijiangtai.m3classroom.application.view;

import java.util.UUID;

public record PublishedQuestionView(
        UUID questionId,
        int order,
        String stem,
        String answerPoints,
        UUID categoryId,
        String categoryName) {
}
