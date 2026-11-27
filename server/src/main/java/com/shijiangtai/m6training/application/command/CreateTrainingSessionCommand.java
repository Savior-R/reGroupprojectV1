package com.shijiangtai.m6training.application.command;

import java.util.List;
import java.util.UUID;

/** Create a publication / personal-template / free training session (08 §6.1). */
public record CreateTrainingSessionCommand(
        UUID templateId,
        UUID publicationId,
        String trainingLevel,
        UUID courseId,
        String topic,
        List<VirtualStudentCommand> virtualStudents,
        UUID materialId) {
}
