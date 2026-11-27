package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingLevel;
import java.util.UUID;

/** Student publication card (08 §10.3); no weights, reference material or template owner. */
public record StudentTemplateCardView(
        UUID publicationId,
        String name,
        String topic,
        UUID courseId,
        String courseName,
        TrainingLevel trainingLevel,
        long durationMs,
        int virtualStudentCount,
        boolean copied) {
}
