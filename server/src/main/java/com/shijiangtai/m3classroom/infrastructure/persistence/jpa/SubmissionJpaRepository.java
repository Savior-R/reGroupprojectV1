package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubmissionJpaRepository extends JpaRepository<SubmissionEntity, UUID> {

    Optional<SubmissionEntity> findByTaskIdAndStudentAccountId(
            UUID taskId, UUID studentAccountId);

    Optional<SubmissionEntity> findBySessionId(UUID sessionId);

    boolean existsBySessionId(UUID sessionId);

    @Query(value = """
            SELECT sub.* FROM m3_classroom.submissions sub
            JOIN m3_classroom.sessions s ON s.id = sub.session_id
            JOIN m2_course.courses c ON c.id = s.course_id
            WHERE c.end_at IS NOT NULL
              AND c.end_at + INTERVAL '1 year' <= :now
            ORDER BY sub.id
            """, nativeQuery = true)
    List<SubmissionEntity> findExpiredForRetention(@Param("now") Instant now);

    void deleteBySessionId(UUID sessionId);
}
