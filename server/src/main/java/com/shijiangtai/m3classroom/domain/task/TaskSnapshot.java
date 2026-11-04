package com.shijiangtai.m3classroom.domain.task;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TaskSnapshot(
        int schemaVersion,
        UUID taskId,
        UUID courseId,
        UUID schoolId,
        String mode,
        String topic,
        String textbookVersion,
        DurationSnapshot duration,
        Map<String, Integer> weights,
        MaterialSnapshot referenceMaterial,
        ScenarioSnapshot scenario,
        List<VirtualStudentSnapshot> virtualStudents,
        InterviewSnapshot interview,
        Instant publishedAt,
        UUID publishedBy) {

    public TaskSnapshot {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(schoolId, "schoolId");
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("mode");
        }
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(duration, "duration");
        weights = weights == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(weights));
        virtualStudents = virtualStudents == null ? List.of() : List.copyOf(virtualStudents);
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(publishedBy, "publishedBy");
    }

    public record DurationSnapshot(int recommendedSeconds, int maxSeconds) {
    }

    public record MaterialSnapshot(
            UUID materialId,
            String filename,
            String objectKey,
            String mime,
            long size,
            String parsedText,
            int charCount) {
    }

    public record ScenarioSnapshot(String role, String situation) {
    }

    public record VirtualStudentSnapshot(
            UUID virtualStudentId,
            String name,
            String avatar,
            String personalityText,
            BigDecimal activity,
            BigDecimal questionRate,
            BigDecimal ability,
            BigDecimal attentionBaseline,
            BigDecimal disciplineProbability,
            int seatNo,
            int sortOrder) {
    }

    public record InterviewSnapshot(
            int totalDurationSeconds, int questionCount, List<QuestionSnapshot> questions) {
        public InterviewSnapshot {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    public record QuestionSnapshot(
            UUID questionId,
            int order,
            String stem,
            String answerPoints,
            UUID categoryId,
            String categoryName) {
    }
}
