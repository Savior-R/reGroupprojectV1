package com.shijiangtai.m6training.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PATCH /api/v1/training/my-templates/{template_id}; null fields are unchanged (08 §4.2). */
public record UpdateTrainingTemplateRequest(
        String name,
        String trainingLevel,
        String topic,
        String textbookVersion,
        DurationRequest duration,
        Map<String, Integer> weights,
        List<VirtualStudentRequest> virtualStudents,
        UUID materialId,
        boolean removeMaterial) {
}
