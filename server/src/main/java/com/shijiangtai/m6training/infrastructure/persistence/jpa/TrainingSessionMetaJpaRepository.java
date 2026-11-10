package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainingSessionMetaJpaRepository
        extends JpaRepository<TrainingSessionMetaEntity, UUID> {

    List<TrainingSessionMetaEntity> findBySessionIdIn(Collection<UUID> sessionIds);

    List<TrainingSessionMetaEntity> findByTemplateId(UUID templateId);
}
