package com.shijiangtai.m5question.api;

import com.shijiangtai.m5question.api.dto.CreateCategoryRequest;
import com.shijiangtai.m5question.api.dto.UpdateCategoryRequest;
import com.shijiangtai.m5question.application.QuestionBankApplicationService;
import com.shijiangtai.m5question.application.command.CreateCategoryCommand;
import com.shijiangtai.m5question.application.command.UpdateCategoryCommand;
import com.shijiangtai.m5question.domain.CategoryView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** M5 category management (04 §5). */
@RestController
@RequestMapping("/api/v1/question-bank/categories")
public class CategoryController {
    private final QuestionBankApplicationService service;
    private final CurrentUserProvider currentUserProvider;

    public CategoryController(
            QuestionBankApplicationService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public List<CategoryView> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return service.listCategories(currentUserProvider.require(), status, keyword);
    }

    @PostMapping
    public CategoryView create(@RequestBody CreateCategoryRequest request) {
        return service.createCategory(
                currentUserProvider.require(),
                new CreateCategoryCommand(request.name(), request.sortOrder()));
    }

    @PatchMapping("/{categoryId}")
    public CategoryView update(
            @PathVariable UUID categoryId, @RequestBody UpdateCategoryRequest request) {
        return service.updateCategory(
                currentUserProvider.require(),
                categoryId,
                new UpdateCategoryCommand(
                        request.name(), request.sortOrder(), request.status()));
    }
}
