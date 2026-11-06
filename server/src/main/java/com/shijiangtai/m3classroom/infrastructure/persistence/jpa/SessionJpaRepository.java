package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionJpaRepository extends JpaRepository<SessionEntity, UUID> {

    /**
     * Non-key-update row lock: serializes recording aggregate mutations per session while still
     * allowing the recording/stream inserts that take a foreign-key key-share lock on this row.
     */
    @Query(value = "SELECT * FROM m3_classroom.sessions WHERE id = :id FOR NO KEY UPDATE",
            nativeQuery = true)
    Optional<SessionEntity> findByIdForUpdate(@Param("id") UUID id);

    List<SessionEntity> findByStudentAccountIdAndTaskIdOrderByAttemptNoAsc(
            UUID studentAccountId, UUID taskId);

    List<SessionEntity> findByStudentAccountIdAndTaskIdAndStatusInOrderByAttemptNoDesc(
            UUID studentAccountId, UUID taskId, Collection<String> statuses);

    List<SessionEntity> findByStatusIn(Collection<String> statuses);

    long countByStudentAccountIdAndTaskId(UUID studentAccountId, UUID taskId);

    @Query(value = """
            SELECT s.* FROM m3_classroom.sessions s
            LEFT JOIN m3_classroom.tasks t ON t.id = s.task_id
            WHERE s.session_type = 'task'
              AND s.status IN ('ended', 'reviewing', 'failed', 'cancelled')
              AND GREATEST(
                    COALESCE(s.ended_at, s.updated_at),
                    COALESCE(t.closed_at, s.ended_at, s.updated_at)
                  ) + INTERVAL '24 hours' <= :now
            ORDER BY s.id
            """, nativeQuery = true)
    List<SessionEntity> findExpiredTemporary(@Param("now") Instant now);
}
