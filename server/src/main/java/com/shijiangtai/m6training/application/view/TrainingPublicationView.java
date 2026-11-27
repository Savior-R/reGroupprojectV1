package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingTemplateSourceType;
import java.time.Instant;
import java.util.UUID;

/** Teacher-facing publication row (08 §10.2). */
public record TrainingPublicationView(
        UUID publicationId,
        UUID templateId,
        String templateName,
        TrainingLevel trainingLevel,
        String topic,
        TrainingTemplateSourceType sourceType,
        UUID courseId,
        String courseName,
        String status,
        Instant publishedAt,
        Instant unpublishedAt,
        String unpublishReason,
        int maxDurationSeconds,
        int virtualStudentCount) {
}
