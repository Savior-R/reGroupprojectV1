package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainingMaterialJpaRepository
        extends JpaRepository<TrainingMaterialEntity, UUID> {

    Optional<TrainingMaterialEntity> findByIdAndStudentAccountId(UUID id, UUID studentAccountId);

    List<TrainingMaterialEntity> findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
            String status, Instant now, Pageable pageable);
}
