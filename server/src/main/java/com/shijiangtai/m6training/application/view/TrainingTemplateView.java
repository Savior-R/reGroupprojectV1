package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingLevel;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Teacher-facing template view; includes weights but never the reference material text. */
public record TrainingTemplateView(
        UUID templateId,
        UUID sourceTaskId,
        int sourceTaskConfigVersion,
        UUID courseId,
        TrainingLevel trainingLevel,
        String status,
        String topic,
        int durationSeconds,
        int maxDurationSeconds,
        int virtualStudentCount,
        boolean hasReferenceMaterial,
        Map<String, Integer> weights,
        Instant publishedAt,
        Instant unpublishedAt,
        Instant createdAt) {
}
