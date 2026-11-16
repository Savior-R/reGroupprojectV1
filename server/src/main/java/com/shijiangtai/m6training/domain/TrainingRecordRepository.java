package com.shijiangtai.m6training.domain;

import com.shijiangtai.shared.domain.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for training records. */
public interface TrainingRecordRepository {

    Optional<TrainingRecord> findById(UUID id);

    Optional<TrainingRecord> findBySessionId(UUID sessionId);

    PageResult<TrainingRecord> listByStudent(
            UUID studentAccountId, TrainingRecordStatus status, Instant now, int page, int size);

    /** Active records of one group, oldest first; templateId null means free training. */
    List<TrainingRecord> listActiveByGroup(UUID studentAccountId, UUID templateId);

    /** Serializes concurrent selections in the same student+template group. */
    void lockGroup(UUID studentAccountId, UUID templateId);

    List<TrainingRecord> listActiveExpired(Instant now, int limit);

    List<TrainingRecord> listPendingDeletion(int limit);

    TrainingRecord save(TrainingRecord record);

    void delete(UUID id);
}
