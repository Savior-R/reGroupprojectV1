package com.shijiangtai.m4eval.api;

import com.shijiangtai.m4eval.application.EvaluationApplicationService;
import com.shijiangtai.m4eval.application.view.EvaluationView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Training evaluation status, owner only (06 §9.1). */
@RestController
@RequestMapping("/api/v1/training/records")
public class TrainingEvaluationController {

    private final EvaluationApplicationService applicationService;
    private final CurrentUserProvider currentUserProvider;

    public TrainingEvaluationController(
            EvaluationApplicationService applicationService,
            CurrentUserProvider currentUserProvider) {
        this.applicationService = applicationService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/{recordId}/evaluation")
    public EvaluationView getByTrainingRecord(@PathVariable UUID recordId) {
        return applicationService.getByTrainingRecord(
                currentUserProvider.require(), recordId);
    }
}
