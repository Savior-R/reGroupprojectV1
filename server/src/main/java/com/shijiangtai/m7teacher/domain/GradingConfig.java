package com.shijiangtai.m7teacher.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Task grading configuration aggregate (07 §3.1 / §4). */
public record GradingConfig(
        UUID id,
        UUID taskId,
        UUID courseId,
        int aiWeight,
        int teacherWeight,
        ScoringMode scoringMode,
        int configVersion,
        Instant lockedAt,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    public GradingConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(scoringMode, "scoringMode");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static GradingConfig defaults(
            UUID id, UUID taskId, UUID courseId, UUID updatedBy, Instant now) {
        return new GradingConfig(id, taskId, courseId, 70, 30, ScoringMode.DIMENSION,
                1, null, updatedBy, now, now);
    }

    public boolean locked() {
        return lockedAt != null;
    }

    public GradingConfig updated(
            int newAiWeight,
            int newTeacherWeight,
            ScoringMode newScoringMode,
            UUID updater,
            Instant now) {
        return new GradingConfig(id, taskId, courseId, newAiWeight, newTeacherWeight,
                newScoringMode, configVersion + 1, lockedAt, updater, createdAt, now);
    }

    public GradingConfig lockedBy(UUID updater, Instant now) {
        return new GradingConfig(id, taskId, courseId, aiWeight, teacherWeight, scoringMode,
                configVersion, now, updater, createdAt, now);
    }

    public GradingConfig unlocked(UUID updater, Instant now) {
        return new GradingConfig(id, taskId, courseId, aiWeight, teacherWeight, scoringMode,
                configVersion, null, updater, createdAt, now);
    }

    /** Validates weights and mode (FR-M7-01). */
    public static void validate(int aiWeight, int teacherWeight, ScoringMode scoringMode) {
        if (aiWeight < 0 || aiWeight > 100
                || teacherWeight < 0 || teacherWeight > 100
                || aiWeight + teacherWeight != 100) {
            throw new M7Exception(M7ErrorCode.GRADING_CONFIG_INVALID,
                    "AI 分权重与教师分权重之和必须为 100，且均在 0—100 之间",
                    Map.of("field", "ai_weight/teacher_weight"));
        }
        if (scoringMode == null) {
            throw new M7Exception(M7ErrorCode.GRADING_CONFIG_INVALID,
                    "打分模式不能为空", Map.of("field", "scoring_mode"));
        }
    }
}
