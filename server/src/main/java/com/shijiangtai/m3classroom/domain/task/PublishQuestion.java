package com.shijiangtai.m3classroom.domain.task;

import java.util.UUID;

public record PublishQuestion(
        UUID questionId,
        String stem,
        String answerPoints,
        UUID categoryId,
        String categoryName,
        UUID schoolId,
        boolean enabled) {
}
