package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingVirtualStudent;

import java.util.List;

/** Student-visible session configuration; no weights and no reference material. */
public record TrainingPublicConfig(
        String topic,
        TrainingLevel trainingLevel,
        long recommendedDurationMs,
        long maxDurationMs,
        int virtualStudentCount,
        List<TrainingVirtualStudent> virtualStudents) {

    public static TrainingPublicConfig of(
            String topic, TrainingLevel level, long recommendedDurationMs,
            long maxDurationMs, List<TrainingVirtualStudent> virtualStudents) {
        return new TrainingPublicConfig(
                topic, level, recommendedDurationMs, maxDurationMs,
                virtualStudents.size(), virtualStudents);
    }
}
