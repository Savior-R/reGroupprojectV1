package com.shijiangtai.m5question.api;

import com.shijiangtai.m5question.api.dto.CreateQuestionRequest;
import com.shijiangtai.m5question.api.dto.UpdateQuestionRequest;
import com.shijiangtai.m5question.application.QuestionBankApplicationService;
import com.shijiangtai.m5question.application.command.CreateQuestionCommand;
import com.shijiangtai.m5question.application.command.QuestionQuery;
import com.shijiangtai.m5question.application.command.UpdateQuestionCommand;
import com.shijiangtai.m5question.domain.QuestionView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.PageResult;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** M5 question management and the read-only M3 selection source (04 §6–§7). */
@RestController
@RequestMapping("/api/v1/question-bank/questions")
public class QuestionController {
    private final QuestionBankApplicationService service;
    private final CurrentUserProvider currentUserProvider;

    public QuestionController(
            QuestionBankApplicationService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public PageResult<QuestionView> list(
            @RequestParam(name = "category_id", required = false) String categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listQuestions(
                currentUserProvider.require(),
                new QuestionQuery(categoryId, status, keyword, page, size));
    }

    @PostMapping
    public QuestionView create(@RequestBody CreateQuestionRequest request) {
        return service.createQuestion(
                currentUserProvider.require(),
                new CreateQuestionCommand(
                        request.categoryId(), request.stem(), request.answerPoints()));
    }

    @PatchMapping("/{questionId}")
    public QuestionView update(
            @PathVariable UUID questionId, @RequestBody UpdateQuestionRequest request) {
        return service.updateQuestion(
                currentUserProvider.require(),
                questionId,
                new UpdateQuestionCommand(
                        request.categoryId(),
                        request.stem(),
                        request.answerPoints(),
                        request.status()));
    }
}
