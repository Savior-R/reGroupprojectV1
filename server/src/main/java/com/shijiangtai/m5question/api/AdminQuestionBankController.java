package com.shijiangtai.m5question.api;

import com.shijiangtai.m5question.application.QuestionBankApplicationService;
import com.shijiangtai.m5question.application.view.DemoInitView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo-only initialization endpoint (04 §7.1). Without the {@code demo} profile the
 * controller is not registered, so the path answers 404 in production.
 */
@RestController
@Profile("demo")
@RequestMapping("/api/v1/admin/question-bank")
public class AdminQuestionBankController {
    private final QuestionBankApplicationService service;
    private final CurrentUserProvider currentUserProvider;

    public AdminQuestionBankController(
            QuestionBankApplicationService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/demo-init")
    public DemoInitView demoInit() {
        return service.initDemo(currentUserProvider.require());
    }
}
