package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingOwnerRole;
import com.shijiangtai.m6training.domain.TrainingTemplateSourceType;
import com.shijiangtai.m6training.domain.TrainingTemplateStatus;
import java.time.Instant;
import java.util.UUID;

/** Personal-template list row (08 §10.1); never exposes parsed text or object keys. */
public record TrainingTemplateSummaryView(
        UUID templateId,
        TrainingOwnerRole ownerRole,
        String name,
        TrainingLevel trainingLevel,
        String topic,
        TrainingTemplateStatus status,
        TrainingTemplateSourceType sourceType,
        long publishedCourseCount,
        int durationSeconds,
        int virtualStudentCount,
        Instant updatedAt) {
}
