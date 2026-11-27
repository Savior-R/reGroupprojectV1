package com.shijiangtai.m6training.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable personal-template configuration snapshot (08 §3.3).
 *
 * <p>{@code schema_version=2} is written for every new template. Historical task-derived
 * templates may still carry {@code schema_version=1} with {@code source_task_id} / {@code course_id};
 * unknown properties are ignored and those fields are no longer modelled.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrainingConfigSnapshot(
        int schemaVersion,
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
        if (classroomMode != null && !TrainingDefaults.CLASSROOM_MODE.equals(classroomMode)) {
            throw new TrainingException(TrainingError.INTERNAL_ERROR, "课堂模式非法");
        }
        classroomMode = TrainingDefaults.CLASSROOM_MODE;
        if (topic == null || topic.isBlank()
                || topic.trim().length() > TrainingDefaults.MAX_TOPIC_LENGTH) {
            throw new TrainingException(TrainingError.TRAINING_TEMPLATE_INVALID,
                    "模板课题非法", Map.of("field", "topic"));
        }
        topic = topic.trim();
        if (textbookVersion != null) {
            textbookVersion = textbookVersion.trim();
            if (textbookVersion.length() > TrainingDefaults.MAX_TEXTBOOK_VERSION_LENGTH) {
                throw new TrainingException(TrainingError.TRAINING_TEMPLATE_INVALID,
                        "教材版本不能超过 100 字", Map.of("field", "textbook_version"));
            }
        }
        weights = weights == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(weights));
        if (weights.isEmpty()
                || weights.values().stream().mapToInt(Integer::intValue).sum() != 100) {
            throw new TrainingException(TrainingError.TRAINING_TEMPLATE_INVALID,
                    "模板权重非法", Map.of("field", "weights"));
        }
        virtualStudents = virtualStudents == null ? List.of() : List.copyOf(virtualStudents);
        TrainingVirtualStudent.requireGroup(virtualStudents);
        Objects.requireNonNull(duration, "duration");
    }

    public static TrainingConfigSnapshot of(
            TrainingLevel level,
            String topic,
            String textbookVersion,
            TrainingDuration duration,
            Map<String, Integer> weights,
            List<TrainingVirtualStudent> virtualStudents,
            TrainingReferenceMaterial referenceMaterial) {
        TrainingDuration effective = level == TrainingLevel.MOCK_EXAM
                ? duration.fixedToMax() : duration;
        return new TrainingConfigSnapshot(
                TrainingDefaults.SCHEMA_VERSION,
                level,
                TrainingDefaults.CLASSROOM_MODE,
                topic,
                textbookVersion,
                effective,
                weights == null || weights.isEmpty() ? TrainingDefaults.weights() : weights,
                virtualStudents == null || virtualStudents.isEmpty()
                        ? TrainingDefaults.defaultVirtualStudents() : virtualStudents,
                referenceMaterial);
    }

    public TrainingLevel levelOr(TrainingLevel fallback) {
        return trainingLevel == null ? fallback : trainingLevel;
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
