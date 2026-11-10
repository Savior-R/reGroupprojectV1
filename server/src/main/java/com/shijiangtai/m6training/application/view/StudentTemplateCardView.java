package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingLevel;

import java.util.UUID;

/** Student template card; no source task, weights or reference material. */
public record StudentTemplateCardView(
        UUID templateId,
        String topic,
        UUID courseId,
        String courseName,
        TrainingLevel trainingLevel,
        long durationMs,
        int virtualStudentCount) {
}
