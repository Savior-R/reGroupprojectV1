package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingDuration;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingReferenceMaterial;
import com.shijiangtai.m6training.domain.TrainingVirtualStudent;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Teacher template prefill for the M3 task editor (08 §10.8).
 *
 * <p>Includes the reference-material object key because M3 copies the object into task-owned
 * storage; this endpoint is teacher-only and reserved for the template owner.
 */
public record TaskTemplateConfigView(
        UUID templateId,
        String name,
        TrainingLevel trainingLevel,
        String topic,
        String textbookVersion,
        TrainingDuration duration,
        Map<String, Integer> weights,
        List<TrainingVirtualStudent> virtualStudents,
        TrainingReferenceMaterial referenceMaterial) {
}
