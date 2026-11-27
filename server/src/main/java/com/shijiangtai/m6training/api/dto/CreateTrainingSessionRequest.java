package com.shijiangtai.m6training.api.dto;

import java.util.List;
import java.util.UUID;

/** POST /api/v1/training/sessions (08 §10.5). */
public record CreateTrainingSessionRequest(
        UUID templateId,
        UUID publicationId,
        String trainingLevel,
        UUID courseId,
        String topic,
        List<VirtualStudentRequest> virtualStudents,
        UUID materialId) {
}
