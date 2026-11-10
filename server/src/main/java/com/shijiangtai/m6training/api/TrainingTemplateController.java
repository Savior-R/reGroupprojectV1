package com.shijiangtai.m6training.api;

import com.shijiangtai.m6training.application.TrainingTemplateService;
import com.shijiangtai.m6training.application.command.PublishTrainingTemplateCommand;
import com.shijiangtai.m6training.application.view.TrainingTemplateView;
import com.shijiangtai.m6training.api.dto.PublishTrainingTemplateRequest;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.PageResult;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Teacher template management (08 §10.1). */
@RestController
@RequestMapping("/api/v1/training/templates")
public class TrainingTemplateController {
    private final TrainingTemplateService service;
    private final CurrentUserProvider currentUserProvider;

    public TrainingTemplateController(
            TrainingTemplateService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public PageResult<TrainingTemplateView> list(
            @RequestParam(name = "training_level", required = false) String level,
            @RequestParam(name = "course_id", required = false) UUID courseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listMine(currentUserProvider.require(), level, courseId, page, size);
    }

    @PostMapping
    public TrainingTemplateView publish(
            @RequestBody PublishTrainingTemplateRequest request) {
        return service.publish(
                currentUserProvider.require(),
                new PublishTrainingTemplateCommand(
                        request.sourceTaskId(), request.courseId(), request.trainingLevel()));
    }

    @PostMapping("/{templateId}/unpublish")
    public void unpublish(@PathVariable UUID templateId) {
        service.unpublish(currentUserProvider.require(), templateId);
    }
}
