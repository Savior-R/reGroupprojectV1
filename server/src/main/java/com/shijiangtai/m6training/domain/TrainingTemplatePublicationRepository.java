package com.shijiangtai.m6training.domain;

import com.shijiangtai.shared.domain.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for course publications of teacher templates. */
public interface TrainingTemplatePublicationRepository {

    Optional<TrainingTemplatePublication> findById(UUID id);

    Optional<TrainingTemplatePublication> findPublished(UUID templateId, UUID courseId);

    List<TrainingTemplatePublication> listByTemplate(UUID templateId);

    List<TrainingTemplatePublication> listPublishedByTemplate(UUID templateId);

    List<TrainingTemplatePublication> listPublished(int limit);

    PageResult<PublicationRow> listByOwner(
            UUID ownerAccountId,
            UUID templateId,
            UUID courseId,
            TrainingPublicationStatus status,
            int page,
            int size);

    /** Student-visible publications: enrolled, course active and not ended, template active. */
    PageResult<PublicationRow> listStudentVisible(
            UUID studentAccountId,
            UUID schoolId,
            TrainingLevel trainingLevel,
            UUID courseId,
            int page,
            int size);

    long countPublishedByTemplate(UUID templateId);

    TrainingTemplatePublication save(TrainingTemplatePublication publication);

    record PublicationRow(
            UUID publicationId,
            UUID templateId,
            String templateName,
            TrainingLevel trainingLevel,
            String topic,
            TrainingTemplateSourceType sourceType,
            UUID courseId,
            String courseName,
            TrainingPublicationStatus status,
            Instant publishedAt,
            Instant unpublishedAt,
            TrainingUnpublishReason unpublishReason,
            int maxDurationSeconds,
            int virtualStudentCount,
            boolean copied) {
    }
}
