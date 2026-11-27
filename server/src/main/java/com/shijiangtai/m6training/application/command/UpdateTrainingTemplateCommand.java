package com.shijiangtai.m6training.application.command;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Partial update of a personal template (08 §4.2). */
public record UpdateTrainingTemplateCommand(
        String name,
        String trainingLevel,
        String topic,
        String textbookVersion,
        Integer recommendedSeconds,
        Integer maxSeconds,
        Map<String, Integer> weights,
        List<VirtualStudentCommand> virtualStudents,
        UUID materialId,
        boolean removeMaterial) {
}
