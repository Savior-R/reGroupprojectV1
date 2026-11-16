package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TrainingTemplateJpaRepository
        extends JpaRepository<TrainingTemplateEntity, UUID>,
        JpaSpecificationExecutor<TrainingTemplateEntity> {

    Optional<TrainingTemplateEntity> findByIdAndCreatedBy(UUID id, UUID createdBy);

    boolean existsBySourceTaskIdAndCourseIdAndTrainingLevelAndStatus(
            UUID sourceTaskId, UUID courseId, String trainingLevel, String status);

    List<TrainingTemplateEntity> findByStatusOrderByUpdatedAtAsc(String status, Pageable pageable);
}
