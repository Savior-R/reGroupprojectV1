package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingDuration;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingOwnerRole;
import com.shijiangtai.m6training.domain.TrainingTemplateSourceType;
import com.shijiangtai.m6training.domain.TrainingVirtualStudent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owner-facing template detail (08 §4.6); configuration fields are editable. */
public record TrainingTemplateDetailView(
        UUID templateId,
        UUID schoolId,
        TrainingOwnerRole ownerRole,
        String name,
        TrainingLevel trainingLevel,
        String topic,
        String textbookVersion,
        TrainingDuration duration,
        Map<String, Integer> weights,
        List<TrainingVirtualStudent> virtualStudents,
        String status,
        TrainingTemplateSourceType sourceType,
        UUID sourceTemplateId,
        UUID sourcePublicationId,
        TrainingMaterialRefView referenceMaterial,
        long publishedCourseCount,
        boolean configLocked,
        Instant createdAt,
        Instant updatedAt) {
}
