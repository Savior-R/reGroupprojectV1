package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TrainingRecordJpaRepository
        extends JpaRepository<TrainingRecordEntity, UUID>,
        JpaSpecificationExecutor<TrainingRecordEntity> {

    Optional<TrainingRecordEntity> findByTrainingSessionId(UUID trainingSessionId);

    List<TrainingRecordEntity> findByStudentAccountIdAndStatusInOrderBySelectedAtAsc(
            UUID studentAccountId, List<String> statuses);

    List<TrainingRecordEntity> findByStudentAccountIdAndTemplateIdAndStatusInOrderBySelectedAtAsc(
            UUID studentAccountId, UUID templateId, List<String> statuses);

    List<TrainingRecordEntity> findByStatusInAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
            List<String> statuses, Instant now, Pageable pageable);

    List<TrainingRecordEntity> findByStatusInOrderByUpdatedAtAsc(
            List<String> statuses, Pageable pageable);
}
