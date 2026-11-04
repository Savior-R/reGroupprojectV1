package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionEventJpaRepository extends JpaRepository<SessionEventEntity, UUID> {

    List<SessionEventEntity> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(
            UUID sessionId, long seq, Pageable pageable);

    @Query(value = "select coalesce(max(seq), 0) from m3_classroom.events"
            + " where session_id = :sessionId", nativeQuery = true)
    long maxSeq(@Param("sessionId") UUID sessionId);
}
