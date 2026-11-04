package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionTextJpaRepository extends JpaRepository<SessionTextEntity, UUID> {

    List<SessionTextEntity> findBySessionIdOrderByAtMsAscIdAsc(UUID sessionId);

    List<SessionTextEntity> findBySessionIdAndSpeakerTypeOrderByAtMsAscIdAsc(
            UUID sessionId, String speakerType);
}
