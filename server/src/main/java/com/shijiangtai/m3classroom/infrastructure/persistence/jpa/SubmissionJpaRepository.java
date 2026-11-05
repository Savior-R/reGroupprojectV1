package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionJpaRepository extends JpaRepository<SubmissionEntity, UUID> {

    Optional<SubmissionEntity> findByTaskIdAndStudentAccountId(
            UUID taskId, UUID studentAccountId);

    Optional<SubmissionEntity> findBySessionId(UUID sessionId);

    boolean existsBySessionId(UUID sessionId);
}
