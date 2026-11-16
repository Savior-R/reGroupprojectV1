package com.shijiangtai.m6training.api;

import com.shijiangtai.m6training.application.TrainingTemplateService;
import com.shijiangtai.m6training.application.view.StudentTemplateCardView;
import com.shijiangtai.m6training.application.view.StudentTemplateDetailView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.PageResult;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Student template entry (08 §10.2); no source task, weights or reference material. */
@RestController
@RequestMapping("/api/v1/student/training/templates")
public class StudentTrainingTemplateController {
    private final TrainingTemplateService service;
    private final CurrentUserProvider currentUserProvider;

    public StudentTrainingTemplateController(
            TrainingTemplateService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public PageResult<StudentTemplateCardView> list(
            @RequestParam(required = false) String level,
            @RequestParam(name = "course_id", required = false) UUID courseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listForStudent(
                currentUserProvider.require(), level, courseId, page, size);
    }

    @GetMapping("/{templateId}")
    public StudentTemplateDetailView get(@PathVariable UUID templateId) {
        return service.getForStudent(currentUserProvider.require(), templateId);
    }
}
