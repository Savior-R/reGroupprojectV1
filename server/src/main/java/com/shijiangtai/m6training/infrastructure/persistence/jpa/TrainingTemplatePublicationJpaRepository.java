package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainingTemplatePublicationJpaRepository
        extends JpaRepository<TrainingTemplatePublicationEntity, UUID> {

    Optional<TrainingTemplatePublicationEntity> findFirstByTemplateIdAndCourseIdAndStatus(
            UUID templateId, UUID courseId, String status);

    List<TrainingTemplatePublicationEntity> findByTemplateIdOrderByPublishedAtDesc(
            UUID templateId);

    List<TrainingTemplatePublicationEntity> findByTemplateIdAndStatusOrderByPublishedAtDesc(
            UUID templateId, String status);

    List<TrainingTemplatePublicationEntity> findByStatusOrderByPublishedAtAsc(
            String status, Pageable pageable);

    long countByTemplateIdAndStatus(UUID templateId, String status);
}
