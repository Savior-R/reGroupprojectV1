package com.shijiangtai.m4eval.api;

import com.shijiangtai.m4eval.application.EvaluationReportQueryService;
import com.shijiangtai.m4eval.application.view.ReportView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Training evaluation report, owner only (06 §9.1 / §9.2, 08 §10.6).
 *
 * <p>M6 reuses this endpoint; it must not expose a second controller for the same path.
 */
@RestController
@RequestMapping("/api/v1/training/records")
public class TrainingEvaluationController {

    private final EvaluationReportQueryService reportQueryService;
    private final CurrentUserProvider currentUserProvider;

    public TrainingEvaluationController(
            EvaluationReportQueryService reportQueryService,
            CurrentUserProvider currentUserProvider) {
        this.reportQueryService = reportQueryService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/{recordId}/evaluation")
    public ReportView getByTrainingRecord(@PathVariable UUID recordId) {
        return reportQueryService.reportByTrainingRecord(
                currentUserProvider.require(), recordId);
    }
}
