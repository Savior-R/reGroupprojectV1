package com.shijiangtai.m6training.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for free-session and template materials. */
public interface TrainingMaterialRepository {

    Optional<TrainingMaterial> findById(UUID id);

    Optional<TrainingMaterial> findByIdAndOwner(UUID id, UUID ownerAccountId);

    List<TrainingMaterial> listAttachedByTemplate(UUID templateId);

    List<TrainingMaterial> listExpiredUploaded(Instant now, int limit);

    TrainingMaterial save(TrainingMaterial material);

    void delete(UUID id);
}
