package com.shijiangtai.m5question.application;

import com.shijiangtai.m5question.domain.Category;
import com.shijiangtai.m5question.domain.CategoryRepository;
import com.shijiangtai.m5question.domain.CategoryView;
import com.shijiangtai.m5question.domain.Question;
import com.shijiangtai.m5question.domain.QuestionRepository;
import com.shijiangtai.m5question.domain.QuestionStatus;
import com.shijiangtai.m5question.domain.QuestionView;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.PageResult;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class QuestionBankQueryServiceImpl implements QuestionBankQueryService {
    private final CategoryRepository categoryRepository;
    private final QuestionRepository questionRepository;
    private final SeedService seedService;

    public QuestionBankQueryServiceImpl(
            CategoryRepository categoryRepository,
            QuestionRepository questionRepository,
            SeedService seedService) {
        this.categoryRepository = categoryRepository;
        this.questionRepository = questionRepository;
        this.seedService = seedService;
    }

    @Override
    public List<CategoryView> listActiveCategories(SchoolId schoolId) {
        seedService.ensureLibrary(schoolId);
        return categoryRepository.listBySchool(
                        schoolId.value(), QuestionStatus.ACTIVE, null).stream()
                .map(CategoryView::of)
                .toList();
    }

    @Override
    public PageResult<QuestionView> listActiveQuestions(
            SchoolId schoolId, UUID categoryId, String keyword, int page, int size) {
        seedService.ensureLibrary(schoolId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<UUID> activeCategoryIds =
                categoryRepository.listBySchool(schoolId.value(), QuestionStatus.ACTIVE, null)
                        .stream().map(Category::id).toList();
        if (categoryId != null && !activeCategoryIds.contains(categoryId)) {
            return new PageResult<>(List.of(), 0, safePage, safeSize);
        }
        PageResult<Question> result = questionRepository.search(
                schoolId.value(), activeCategoryIds, categoryId, QuestionStatus.ACTIVE,
                keyword, safePage, safeSize);
        Map<UUID, String> names = categoryNames(schoolId.value());
        return new PageResult<>(
                result.items().stream()
                        .map(question -> QuestionView.of(
                                question, names.getOrDefault(question.categoryId(), "")))
                        .toList(),
                result.total(),
                result.page(),
                result.size());
    }

    @Override
    public QuestionView getQuestion(SchoolId schoolId, UUID questionId) {
        seedService.ensureLibrary(schoolId);
        Question question = questionRepository.findBySchoolAndId(schoolId.value(), questionId)
                .orElseGet(() -> crossSchoolOrMissing(
                        questionRepository.findById(questionId).isPresent(),
                        Map.of("question_id", questionId)));
        String categoryName = categoryRepository
                .findBySchoolAndId(schoolId.value(), question.categoryId())
                .map(Category::name)
                .orElse("");
        return QuestionView.of(question, categoryName);
    }

    @Override
    public List<QuestionView> getQuestions(SchoolId schoolId, List<UUID> questionIds) {
        seedService.ensureLibrary(schoolId);
        if (questionIds == null || questionIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> names = categoryNames(schoolId.value());
        return questionRepository.findBySchoolAndIds(schoolId.value(), questionIds).stream()
                .map(question -> QuestionView.of(
                        question, names.getOrDefault(question.categoryId(), "")))
                .toList();
    }

    private Map<UUID, String> categoryNames(UUID schoolId) {
        return categoryRepository.listBySchool(schoolId, null, null).stream()
                .collect(Collectors.toMap(Category::id, Category::name, (left, right) -> left));
    }

    private <T> T crossSchoolOrMissing(boolean existsElsewhere, Map<String, Object> details) {
        throw new DomainException(
                existsElsewhere ? ErrorCode.CROSS_SCHOOL_ACCESS : ErrorCode.RESOURCE_NOT_FOUND,
                "题目不存在", details);
    }
}
