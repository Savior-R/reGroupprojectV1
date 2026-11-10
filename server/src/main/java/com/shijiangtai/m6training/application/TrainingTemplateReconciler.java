package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingTemplateRepository;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort.CourseInfo;
import com.shijiangtai.shared.application.ClockProvider;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Five-minute safety net for lost M2 events (08 §4.3): a template is unpublished when its target
 * course is no longer active or its creator left the target course teacher group.
 */
@Component
public class TrainingTemplateReconciler {
    private static final Logger log = LoggerFactory.getLogger(TrainingTemplateReconciler.class);
    private static final int BATCH = 200;

    private final TrainingTemplateRepository templateRepository;
    private final CourseDirectoryPort courseDirectoryPort;
    private final TrainingTemplateLifecycle lifecycle;
    private final ClockProvider clock;

    public TrainingTemplateReconciler(
            TrainingTemplateRepository templateRepository,
            CourseDirectoryPort courseDirectoryPort,
            TrainingTemplateLifecycle lifecycle,
            ClockProvider clock) {
        this.templateRepository = templateRepository;
        this.courseDirectoryPort = courseDirectoryPort;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    public void reconcile() {
        Instant now = clock.now();
        for (TrainingTemplate template : templateRepository.listPublished(BATCH)) {
            Optional<CourseInfo> course = courseDirectoryPort.findCourse(template.courseId());
            boolean shouldUnpublish = course.isEmpty()
                    || !course.get().active()
                    || (course.get().endAt() != null && !course.get().endAt().isAfter(now))
                    || !courseDirectoryPort.isActiveTeacher(
                            template.courseId(), template.createdBy());
            if (!shouldUnpublish) {
                continue;
            }
            try {
                lifecycle.unpublish(template, now);
            } catch (RuntimeException exception) {
                log.warn("Failed to auto-unpublish template {}", template.id(), exception);
            }
        }
    }
}
