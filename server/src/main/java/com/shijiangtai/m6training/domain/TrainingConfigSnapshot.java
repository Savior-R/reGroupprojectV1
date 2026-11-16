package com.shijiangtai.m6training.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable template configuration snapshot (08 §3.2). */
public record TrainingConfigSnapshot(
        int schemaVersion,
        UUID sourceTaskId,
        int sourceTaskConfigVersion,
        UUID courseId,
        TrainingLevel trainingLevel,
        String classroomMode,
        String topic,
        String textbookVersion,
        TrainingDuration duration,
        Map<String, Integer> weights,
        List<TrainingVirtualStudent> virtualStudents,
        TrainingReferenceMaterial referenceMaterial) {

    public TrainingConfigSnapshot {
        if (schemaVersion < 1) {
            throw new TrainingException(TrainingError.INTERNAL_ERROR, "快照版本非法");
        }
        Objects.requireNonNull(sourceTaskId, "sourceTaskId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(trainingLevel, "trainingLevel");
        if (!"free".equals(classroomMode)) {
            throw new TrainingException(TrainingError.INTERNAL_ERROR, "课堂模式非法");
        }
        if (topic == null || topic.isBlank() || topic.trim().length() > 200) {
            throw new TrainingException(TrainingError.TRAINING_SOURCE_INVALID,
                    "源任务课题非法", Map.of("field", "topic"));
        }
        topic = topic.trim();
        weights = weights == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(weights));
        if (weights.isEmpty() || weights.values().stream().mapToInt(Integer::intValue).sum() != 100) {
            throw new TrainingException(TrainingError.TRAINING_SOURCE_INVALID,
                    "源任务权重非法", Map.of("field", "weights"));
        }
        virtualStudents = virtualStudents == null ? List.of() : List.copyOf(virtualStudents);
        TrainingVirtualStudent.requireGroup(virtualStudents);
    }

    public Map<String, Object> summary() {
        return Map.of(
                "topic", topic,
                "textbook_version", textbookVersion == null ? "" : textbookVersion,
                "duration_seconds", duration.maxSeconds(),
                "virtual_student_count", virtualStudents.size(),
                "has_reference_material", referenceMaterial != null);
    }
}
