package com.shijiangtai.m7teacher.api;

import com.shijiangtai.m7teacher.application.StudentResultQueryService;
import com.shijiangtai.m7teacher.application.view.StudentResultDetailView;
import com.shijiangtai.m7teacher.application.view.StudentResultListView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class StudentResultController {

    private final StudentResultQueryService studentResultQueryService;
    private final CurrentUserProvider currentUserProvider;

    public StudentResultController(
            StudentResultQueryService studentResultQueryService,
            CurrentUserProvider currentUserProvider) {
        this.studentResultQueryService = studentResultQueryService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/student/results")
    public StudentResultListView list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return studentResultQueryService.list(currentUserProvider.require(), page, size);
    }

    @GetMapping("/submissions/{submissionId}/result")
    public StudentResultDetailView detail(@PathVariable UUID submissionId) {
        return studentResultQueryService.detail(
                currentUserProvider.require(), submissionId);
    }
}
