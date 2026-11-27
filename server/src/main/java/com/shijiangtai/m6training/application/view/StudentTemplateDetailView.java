package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingVirtualStudent;
import java.util.List;
import java.util.UUID;

/** Public publication detail (08 §10.3): dimension keys only, never weights or material. */
public record StudentTemplateDetailView(
        UUID publicationId,
        String name,
        String topic,
        UUID courseId,
        String courseName,
        TrainingLevel trainingLevel,
        long recommendedDurationMs,
        long maxDurationMs,
        List<String> dimensions,
        List<TrainingVirtualStudent> virtualStudents,
        boolean copied) {
}
