package com.shijiangtai.m4eval.api;

import com.shijiangtai.m4eval.application.EvaluationApplicationService;
import com.shijiangtai.m4eval.application.EvaluationReportQueryService;
import com.shijiangtai.m4eval.application.view.EvaluationView;
import com.shijiangtai.m4eval.application.view.IssueView;
import com.shijiangtai.m4eval.application.view.ReportView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public evaluation endpoints (06 §9.1). */
@RestController
@RequestMapping("/api/v1")
public class EvaluationController {

    private final EvaluationApplicationService applicationService;
    private final EvaluationReportQueryService reportQueryService;
    private final CurrentUserProvider currentUserProvider;

    public EvaluationController(
            EvaluationApplicationService applicationService,
            EvaluationReportQueryService reportQueryService,
            CurrentUserProvider currentUserProvider) {
        this.applicationService = applicationService;
        this.reportQueryService = reportQueryService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/submissions/{submissionId}/evaluation")
    public EvaluationView getBySubmission(@PathVariable UUID submissionId) {
        return applicationService.getBySubmission(
                currentUserProvider.require(), submissionId);
    }

    @GetMapping("/evaluations/{evaluationId}")
    public EvaluationView get(@PathVariable UUID evaluationId) {
        return applicationService.get(currentUserProvider.require(), evaluationId);
    }

    @GetMapping("/evaluations/{evaluationId}/report")
    public ReportView report(@PathVariable UUID evaluationId) {
        return reportQueryService.report(currentUserProvider.require(), evaluationId);
    }

    @GetMapping("/evaluations/{evaluationId}/issues")
    public List<IssueView> issues(@PathVariable UUID evaluationId) {
        return reportQueryService.issues(currentUserProvider.require(), evaluationId);
    }
}
