package com.shijiangtai.m6training.api;

import com.shijiangtai.m6training.api.dto.PublishTrainingTemplateRequest;
import com.shijiangtai.m6training.application.TrainingTemplatePublicationService;
import com.shijiangtai.m6training.application.command.PublishTrainingTemplateCommand;
import com.shijiangtai.m6training.application.view.TrainingPublicationView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.PageResult;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Course publications of teacher templates (08 §10.2). */
@RestController
@RequestMapping("/api/v1/training")
public class TrainingTemplatePublicationController {
    private final TrainingTemplatePublicationService service;
    private final CurrentUserProvider currentUserProvider;

    public TrainingTemplatePublicationController(
            TrainingTemplatePublicationService service,
            CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/publications")
    public PageResult<TrainingPublicationView> list(
            @RequestParam(name = "template_id", required = false) UUID templateId,
            @RequestParam(name = "course_id", required = false) UUID courseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listMine(
                currentUserProvider.require(), templateId, courseId, status, page, size);
    }

    @PostMapping("/my-templates/{templateId}/publications")
    public TrainingPublicationView publish(
            @PathVariable UUID templateId,
            @RequestBody PublishTrainingTemplateRequest request) {
        return service.publish(
                currentUserProvider.require(),
                templateId,
                new PublishTrainingTemplateCommand(request.courseId()));
    }

    @PostMapping("/publications/{publicationId}/unpublish")
    public ResponseEntity<Void> unpublish(@PathVariable UUID publicationId) {
        service.unpublish(currentUserProvider.require(), publicationId);
        return ResponseEntity.noContent().build();
    }
}
