package com.shijiangtai.m6training.domain;

import com.shijiangtai.shared.domain.PageResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for personal training templates. */
public interface TrainingTemplateRepository {

    Optional<TrainingTemplate> findById(UUID id);

    Optional<TrainingTemplate> findByIdAndOwner(UUID id, UUID ownerAccountId);

    PageResult<TrainingTemplate> listByOwner(
            UUID ownerAccountId,
            TrainingOwnerRole ownerRole,
            TrainingLevel trainingLevel,
            TrainingTemplateStatus status,
            int page,
            int size);

    /** Active templates only, used by template pickers and task prefill. */
    List<TrainingTemplate> listActiveByOwner(UUID ownerAccountId);

    long countActiveByOwner(UUID ownerAccountId);

    boolean existsActiveCopy(UUID ownerAccountId, UUID sourcePublicationId);

    /** Serializes quota checks for one owner inside the current transaction. */
    void lockOwner(UUID ownerAccountId);

    TrainingTemplate save(TrainingTemplate template);
}
