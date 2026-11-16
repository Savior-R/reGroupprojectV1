package com.shijiangtai.m6training.application.command;

import java.util.List;
import java.util.UUID;

/** Create a template or free training session (08 §10.4). */
public record CreateTrainingSessionCommand(
        UUID templateId,
        String trainingLevel,
        UUID courseId,
        String topic,
        List<VirtualStudentCommand> virtualStudents,
        UUID materialId) {
}
