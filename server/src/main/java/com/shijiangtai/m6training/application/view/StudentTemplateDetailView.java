package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingVirtualStudent;

import java.util.List;
import java.util.UUID;

/** Public template detail (08 §10.2). Dimensions are names only, never weights. */
public record StudentTemplateDetailView(
        UUID templateId,
        String topic,
        UUID courseId,
        String courseName,
        TrainingLevel trainingLevel,
        long durationMs,
        List<String> dimensions,
        List<TrainingVirtualStudent> virtualStudents) {
}
