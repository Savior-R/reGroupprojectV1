package com.shijiangtai.m7teacher.infrastructure.persistence.jpa;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GradingConfigJpaRepository extends JpaRepository<GradingConfigEntity, UUID> {

    Optional<GradingConfigEntity> findByTaskId(UUID taskId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO m7_teacher.task_grading_configs
                (id, task_id, course_id, ai_weight, teacher_weight, scoring_mode,
                 config_version, locked_at, updated_by, created_at, updated_at)
            VALUES
                (:id, :taskId, :courseId, :aiWeight, :teacherWeight, :scoringMode,
                 :configVersion, :lockedAt, :updatedBy, :createdAt, :updatedAt)
            ON CONFLICT (task_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("taskId") UUID taskId,
            @Param("courseId") UUID courseId,
            @Param("aiWeight") int aiWeight,
            @Param("teacherWeight") int teacherWeight,
            @Param("scoringMode") String scoringMode,
            @Param("configVersion") int configVersion,
            @Param("lockedAt") Instant lockedAt,
            @Param("updatedBy") UUID updatedBy,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt);
}
