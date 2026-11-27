package com.shijiangtai.m6training.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** POST /api/v1/training/my-templates (08 §4.1). */
public record CreateTrainingTemplateRequest(
        String ownerRole,
        String name,
        String trainingLevel,
        String topic,
        String textbookVersion,
        DurationRequest duration,
        Map<String, Integer> weights,
        List<VirtualStudentRequest> virtualStudents,
        UUID materialId) {
}
