package com.shijiangtai.m6training.application.command;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Create a personal template from scratch (08 §4.1). */
public record CreateTrainingTemplateCommand(
        String ownerRole,
        String name,
        String trainingLevel,
        String topic,
        String textbookVersion,
        Integer recommendedSeconds,
        Integer maxSeconds,
        Map<String, Integer> weights,
        List<VirtualStudentCommand> virtualStudents,
        UUID materialId) {
}
