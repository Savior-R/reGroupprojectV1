package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.domain.TrainingSessionStatus;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingTemplateRepository;
import com.shijiangtai.m6training.domain.TrainingSessionMetaRepository;
import com.shijiangtai.m6training.domain.port.TrainingClassroomPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Shared unpublish side effects: keep the template row, cancel only not-started sessions. */
@Component
public class TrainingTemplateLifecycle {
    private final TrainingTemplateRepository templateRepository;
    private final TrainingSessionMetaRepository sessionMetaRepository;
    private final TrainingClassroomPort classroomPort;

    public TrainingTemplateLifecycle(
            TrainingTemplateRepository templateRepository,
            TrainingSessionMetaRepository sessionMetaRepository,
            TrainingClassroomPort classroomPort) {
        this.templateRepository = templateRepository;
        this.sessionMetaRepository = sessionMetaRepository;
        this.classroomPort = classroomPort;
    }

    public void unpublish(TrainingTemplate template, Instant now) {
        if (!template.isPublished()) {
            return;
        }
        template.unpublish(now);
        templateRepository.save(template);
        for (UUID sessionId
                : sessionMetaRepository.listPreparingSessionIdsByTemplate(template.id())) {
            classroomPort.updateStatus(
                    sessionId, TrainingSessionStatus.CANCELLED.wireValue(), now, null);
        }
    }
}
