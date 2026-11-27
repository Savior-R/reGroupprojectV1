package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.domain.TrainingOwnerRole;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingTemplatePublication;
import com.shijiangtai.m6training.domain.TrainingTemplatePublicationRepository;
import com.shijiangtai.m6training.domain.TrainingTemplateRepository;
import com.shijiangtai.m6training.domain.TrainingUnpublishReason;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort.CourseInfo;
import com.shijiangtai.shared.application.ClockProvider;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Five-minute safety net for lost M2 events (08 §4.5): a publication is taken down when its
 * template is no longer an active teacher template, its course is archived, or its owner left
 * the course teacher group.
 */
@Component
public class TrainingTemplateReconciler {
    private static final Logger log = LoggerFactory.getLogger(TrainingTemplateReconciler.class);
    private static final int BATCH = 200;

    private final TrainingTemplatePublicationRepository publicationRepository;
    private final TrainingTemplateRepository templateRepository;
    private final CourseDirectoryPort courseDirectoryPort;
    private final TrainingTemplateLifecycle lifecycle;
    private final ClockProvider clock;

    public TrainingTemplateReconciler(
            TrainingTemplatePublicationRepository publicationRepository,
            TrainingTemplateRepository templateRepository,
            CourseDirectoryPort courseDirectoryPort,
            TrainingTemplateLifecycle lifecycle,
            ClockProvider clock) {
        this.publicationRepository = publicationRepository;
        this.templateRepository = templateRepository;
        this.courseDirectoryPort = courseDirectoryPort;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    public void reconcile() {
        Instant now = clock.now();
        for (TrainingTemplatePublication publication
                : publicationRepository.listPublished(BATCH)) {
            TrainingTemplate template = templateRepository
                    .findById(publication.templateId()).orElse(null);
            if (template == null
                    || !template.isActive()
                    || template.ownerRole() != TrainingOwnerRole.TEACHER) {
                unpublish(publication, TrainingUnpublishReason.TEMPLATE_DELETED, now);
                continue;
            }
            Optional<CourseInfo> course = courseDirectoryPort.findCourse(publication.courseId());
            if (course.isEmpty() || !course.get().active()) {
                unpublish(publication, TrainingUnpublishReason.COURSE_ARCHIVED, now);
                continue;
            }
            if (!courseDirectoryPort.isActiveTeacher(
                    publication.courseId(), template.ownerAccountId())) {
                unpublish(publication, TrainingUnpublishReason.TEACHER_REMOVED, now);
            }
        }
    }

    private void unpublish(
            TrainingTemplatePublication publication,
            TrainingUnpublishReason reason,
            Instant now) {
        try {
            publication.unpublish(reason, now);
            publicationRepository.save(publication);
            lifecycle.cancelPreparingSessions(publication.templateId(), now);
        } catch (RuntimeException exception) {
            log.warn("Failed to auto-unpublish publication {}", publication.id(), exception);
        }
    }
}
