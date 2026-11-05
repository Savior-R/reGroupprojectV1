package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingJpaRepository extends JpaRepository<RecordingEntity, UUID> {

    Optional<RecordingEntity> findBySessionId(UUID sessionId);
}
