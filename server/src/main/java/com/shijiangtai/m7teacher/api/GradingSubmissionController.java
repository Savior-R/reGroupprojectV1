package com.shijiangtai.m7teacher.api;

import com.shijiangtai.m7teacher.api.dto.GradingSubmissionResponse;
import com.shijiangtai.m7teacher.application.GradingSubmissionQueryService;
import com.shijiangtai.m7teacher.application.view.GradingQuery;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GradingSubmissionController {

    private final GradingSubmissionQueryService queryService;
    private final CurrentUserProvider currentUserProvider;

    public GradingSubmissionController(
            GradingSubmissionQueryService queryService,
            CurrentUserProvider currentUserProvider) {
        this.queryService = queryService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/tasks/{taskId}/grading-submissions")
    public GradingSubmissionResponse list(
            @PathVariable UUID taskId,
            @RequestParam(name = "class_id", required = false) UUID classId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return GradingSubmissionResponse.from(queryService.list(
                currentUserProvider.require(), taskId,
                new GradingQuery(classId, status, keyword, page, size)));
    }
}
