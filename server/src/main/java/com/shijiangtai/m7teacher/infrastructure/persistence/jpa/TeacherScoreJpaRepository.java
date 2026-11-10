package com.shijiangtai.m7teacher.infrastructure.persistence.jpa;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeacherScoreJpaRepository
        extends JpaRepository<TeacherScoreEntity, UUID> {

    Optional<TeacherScoreEntity> findBySubmissionId(UUID submissionId);

    List<TeacherScoreEntity> findBySubmissionIdIn(Collection<UUID> submissionIds);

    List<TeacherScoreEntity> findByTaskId(UUID taskId);

    long countByTaskId(UUID taskId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TeacherScoreEntity t
               set t.scoringMode = :scoringMode,
                   t.dimensionScores = :dimensionScores,
                   t.teacherTotal = :teacherTotal,
                   t.comment = :comment,
                   t.version = t.version + 1,
                   t.gradedBy = :gradedBy,
                   t.gradedAt = :gradedAt,
                   t.updatedAt = :updatedAt
             where t.submissionId = :submissionId
               and t.version = :version
            """)
    int updateIfVersion(
            @Param("submissionId") UUID submissionId,
            @Param("version") int version,
            @Param("scoringMode") String scoringMode,
            @Param("dimensionScores") String dimensionScores,
            @Param("teacherTotal") BigDecimal teacherTotal,
            @Param("comment") String comment,
            @Param("gradedBy") UUID gradedBy,
            @Param("gradedAt") Instant gradedAt,
            @Param("updatedAt") Instant updatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TeacherScoreEntity t where t.taskId = :taskId")
    int deleteByTaskId(@Param("taskId") UUID taskId);
}
