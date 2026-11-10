package com.shijiangtai.m7teacher.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One effective teacher score per submission (07 §3.2). */
public record TeacherScore(
        UUID id,
        UUID submissionId,
        UUID taskId,
        UUID courseId,
        UUID studentAccountId,
        UUID classIdSnapshot,
        ScoringMode scoringMode,
        Map<String, BigDecimal> dimensionScores,
        BigDecimal teacherTotal,
        String comment,
        int version,
        UUID gradedBy,
        Instant gradedAt,
        Instant updatedAt) {

    public TeacherScore {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(submissionId, "submissionId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(studentAccountId, "studentAccountId");
        Objects.requireNonNull(scoringMode, "scoringMode");
        Objects.requireNonNull(teacherTotal, "teacherTotal");
        Objects.requireNonNull(gradedBy, "gradedBy");
        Objects.requireNonNull(gradedAt, "gradedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        dimensionScores = dimensionScores == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(dimensionScores));
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    public TeacherScore nextVersion(
            Map<String, BigDecimal> newDimensions,
            BigDecimal newTotal,
            String newComment,
            UUID grader,
            Instant now) {
        return new TeacherScore(id, submissionId, taskId, courseId, studentAccountId,
                classIdSnapshot, scoringMode, newDimensions, newTotal, newComment,
                version + 1, grader, now, now);
    }
}
