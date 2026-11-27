package com.shijiangtai.m6training.application;

import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import com.shijiangtai.m6training.application.event.TrainingRecordCleanupRequestedEvent;
import com.shijiangtai.m6training.domain.TrainingMaterial;
import com.shijiangtai.m6training.domain.TrainingMaterialRepository;
import com.shijiangtai.m6training.domain.TrainingRecord;
import com.shijiangtai.m6training.domain.TrainingRecordRepository;
import com.shijiangtai.m6training.domain.TrainingSessionMetaRepository;
import com.shijiangtai.m6training.domain.TrainingSessionStatus;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingTemplatePublication;
import com.shijiangtai.m6training.domain.TrainingTemplatePublicationRepository;
import com.shijiangtai.m6training.domain.TrainingTemplateRepository;
import com.shijiangtai.m6training.domain.TrainingUnpublishReason;
import com.shijiangtai.m6training.domain.port.TrainingClassroomPort;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Shared template / publication side effects (08 §4.3, §4.5, §6). */
@Component
public class TrainingTemplateLifecycle {
    private static final Logger log = LoggerFactory.getLogger(TrainingTemplateLifecycle.class);

    private final TrainingTemplateRepository templateRepository;
    private final TrainingTemplatePublicationRepository publicationRepository;
    private final TrainingSessionMetaRepository sessionMetaRepository;
    private final TrainingRecordRepository recordRepository;
    private final TrainingMaterialRepository materialRepository;
    private final TrainingClassroomPort classroomPort;
    private final MaterialObjectStorage materialStorage;
    private final ApplicationEventPublisher eventPublisher;

    public TrainingTemplateLifecycle(
            TrainingTemplateRepository templateRepository,
            TrainingTemplatePublicationRepository publicationRepository,
            TrainingSessionMetaRepository sessionMetaRepository,
            TrainingRecordRepository recordRepository,
            TrainingMaterialRepository materialRepository,
            TrainingClassroomPort classroomPort,
            MaterialObjectStorage materialStorage,
            ApplicationEventPublisher eventPublisher) {
        this.templateRepository = templateRepository;
        this.publicationRepository = publicationRepository;
        this.sessionMetaRepository = sessionMetaRepository;
        this.recordRepository = recordRepository;
        this.materialRepository = materialRepository;
        this.classroomPort = classroomPort;
        this.materialStorage = materialStorage;
        this.eventPublisher = eventPublisher;
    }

    /** Takes down every published course of a template and cancels not-started sessions. */
    public void unpublishAll(
            UUID templateId, TrainingUnpublishReason reason, Instant now) {
        for (TrainingTemplatePublication publication
                : publicationRepository.listPublishedByTemplate(templateId)) {
            publication.unpublish(reason, now);
            publicationRepository.save(publication);
        }
        cancelPreparingSessions(templateId, now);
    }

    public void cancelPreparingSessions(UUID templateId, Instant now) {
        for (UUID sessionId
                : sessionMetaRepository.listPreparingSessionIdsByTemplate(templateId)) {
            classroomPort.updateStatus(
                    sessionId, TrainingSessionStatus.CANCELLED.wireValue(), now, null);
        }
    }

    /**
     * Soft-deletes a template and cleans everything it owns (08 §4.3): publications first,
     * then records / recordings / reports, then attached materials.
     */
    public void archiveAndClean(TrainingTemplate template, Instant now) {
        unpublishAll(template.id(), TrainingUnpublishReason.TEMPLATE_DELETED, now);
        template.archive(now);
        templateRepository.save(template);

        for (TrainingRecord record : recordRepository.listByTemplate(template.id())) {
            if (record.status().active()) {
                record.markDeleted(now);
                recordRepository.save(record);
            }
            eventPublisher.publishEvent(
                    new TrainingRecordCleanupRequestedEvent(record.id()));
        }

        for (TrainingMaterial material
                : materialRepository.listAttachedByTemplate(template.id())) {
            try {
                materialStorage.delete(material.objectKey());
            } catch (RuntimeException exception) {
                log.warn("Failed to delete template material object {}", material.id(), exception);
            }
            material.markDeleted();
            materialRepository.save(material);
        }
    }
}
