package com.shijiangtai.m6training.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for free-training materials. */
public interface TrainingMaterialRepository {

    Optional<TrainingMaterial> findByIdAndStudent(UUID id, UUID studentAccountId);

    List<TrainingMaterial> listExpiredUploaded(Instant now, int limit);

    TrainingMaterial save(TrainingMaterial material);

    void delete(UUID id);
}
