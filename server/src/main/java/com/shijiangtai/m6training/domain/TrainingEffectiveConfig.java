package com.shijiangtai.m6training.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Per-session immutable configuration (08 §5.4). */
public record TrainingEffectiveConfig(
        int schemaVersion,
        UUID templateId,
        TrainingLevel trainingLevel,
        UUID courseId,
        String topic,
        TrainingDuration duration,
        Map<String, Integer> weights,
        List<TrainingVirtualStudent> virtualStudents,
        TrainingReferenceMaterial referenceMaterial) {

    public TrainingEffectiveConfig {
        if (schemaVersion < 1) {
            throw new TrainingException(TrainingError.INTERNAL_ERROR, "快照版本非法");
        }
        Objects.requireNonNull(trainingLevel, "trainingLevel");
        if (topic == null || topic.isBlank() || topic.trim().length() > 200) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "课题非法", Map.of("field", "topic"));
        }
        topic = topic.trim();
        weights = weights == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(weights));
        if (weights.isEmpty() || weights.values().stream().mapToInt(Integer::intValue).sum() != 100) {
            throw new TrainingException(TrainingError.INTERNAL_ERROR, "权重非法");
        }
        virtualStudents = virtualStudents == null ? List.of() : List.copyOf(virtualStudents);
        TrainingVirtualStudent.requireGroup(virtualStudents);
        Objects.requireNonNull(duration, "duration");
    }
}
