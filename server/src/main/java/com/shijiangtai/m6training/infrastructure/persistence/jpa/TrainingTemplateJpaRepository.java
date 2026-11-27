package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TrainingTemplateJpaRepository
        extends JpaRepository<TrainingTemplateEntity, UUID>,
        JpaSpecificationExecutor<TrainingTemplateEntity> {

    Optional<TrainingTemplateEntity> findByIdAndOwnerAccountId(UUID id, UUID ownerAccountId);

    long countByOwnerAccountIdAndStatus(UUID ownerAccountId, String status);

    List<TrainingTemplateEntity> findByOwnerAccountIdAndStatusOrderByUpdatedAtDesc(
            UUID ownerAccountId, String status);

    boolean existsByOwnerAccountIdAndSourcePublicationIdAndStatus(
            UUID ownerAccountId, UUID sourcePublicationId, String status);
}
