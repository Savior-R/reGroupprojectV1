package com.shijiangtai.m5question.application;

import com.shijiangtai.m5question.application.command.CreateCategoryCommand;
import com.shijiangtai.m5question.application.command.CreateQuestionCommand;
import com.shijiangtai.m5question.application.command.QuestionQuery;
import com.shijiangtai.m5question.application.command.UpdateCategoryCommand;
import com.shijiangtai.m5question.application.command.UpdateQuestionCommand;
import com.shijiangtai.m5question.application.view.DemoInitView;
import com.shijiangtai.m5question.domain.Category;
import com.shijiangtai.m5question.domain.CategoryRepository;
import com.shijiangtai.m5question.domain.CategoryView;
import com.shijiangtai.m5question.domain.Library;
import com.shijiangtai.m5question.domain.LibraryRepository;
import com.shijiangtai.m5question.domain.Question;
import com.shijiangtai.m5question.domain.QuestionRepository;
import com.shijiangtai.m5question.domain.QuestionStatus;
import com.shijiangtai.m5question.domain.QuestionView;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.PageResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionBankApplicationServiceImpl implements QuestionBankApplicationService {
    private final CategoryRepository categoryRepository;
    private final QuestionRepository questionRepository;
    private final LibraryRepository libraryRepository;
    private final SeedService seedService;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;

    public QuestionBankApplicationServiceImpl(
            CategoryRepository categoryRepository,
            QuestionRepository questionRepository,
            LibraryRepository libraryRepository,
            SeedService seedService,
            IdGenerator idGenerator,
            ClockProvider clockProvider) {
        this.categoryRepository = categoryRepository;
        this.questionRepository = questionRepository;
        this.libraryRepository = libraryRepository;
        this.seedService = seedService;
        this.idGenerator = idGenerator;
        this.clockProvider = clockProvider;
    }

    @Override
    public List<CategoryView> listCategories(CurrentUser user, String status, String keyword) {
        CurrentUser reader = M5Access.requireReader(user);
        seedService.ensureLibrary(reader.schoolId());
        QuestionStatus filter = status == null || status.isBlank()
                ? null : QuestionStatus.fromWire(status);
        return categoryRepository
                .listBySchool(reader.schoolId().value(), filter, keyword).stream()
                .map(CategoryView::of)
                .toList();
    }

    @Override
    @Transactional
    public CategoryView createCategory(CurrentUser user, CreateCategoryCommand command) {
        CurrentUser manager = M5Access.requireManager(user);
        seedService.ensureLibrary(manager.schoolId());
        UUID schoolId = manager.schoolId().value();
        Category category = Category.create(
                idGenerator.nextId(),
                schoolId,
                command.name(),
                command.sortOrder() == null
                        ? categoryRepository.nextSortOrder(schoolId) : command.sortOrder(),
                manager.accountId().value(),
                clockProvider.now());
        requireUniqueName(schoolId, category.name(), null);
        try {
            categoryRepository.save(category);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName(category.name());
        }
        return CategoryView.of(category);
    }

    @Override
    @Transactional
    public CategoryView updateCategory(
            CurrentUser user, UUID categoryId, UpdateCategoryCommand command) {
        CurrentUser manager = M5Access.requireManager(user);
        seedService.ensureLibrary(manager.schoolId());
        UUID schoolId = manager.schoolId().value();
        Category category = requireCategory(schoolId, categoryId);
        QuestionStatus status = command.status() == null || command.status().isBlank()
                ? null : QuestionStatus.fromWire(command.status());
        String newName = command.name() == null ? null : command.name().trim();
        if (newName != null && !newName.equals(category.name())) {
            requireUniqueName(schoolId, newName, categoryId);
        }
        category.update(newName, command.sortOrder(), status,
                manager.accountId().value(), clockProvider.now());
        try {
            categoryRepository.save(category);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName(category.name());
        }
        return CategoryView.of(category);
    }

    @Override
    public PageResult<QuestionView> listQuestions(CurrentUser user, QuestionQuery query) {
        CurrentUser reader = M5Access.requireReader(user);
        seedService.ensureLibrary(reader.schoolId());
        UUID schoolId = reader.schoolId().value();
        UUID categoryId = parseUuid(query.categoryId(), "category_id");
        QuestionStatus status = query.status() == null || query.status().isBlank()
                ? null : QuestionStatus.fromWire(query.status());
        List<UUID> activeCategoryIds = null;
        if (status == QuestionStatus.ACTIVE) {
            // Disabled categories disappear from selection lists (04 §12).
            activeCategoryIds = categoryRepository.listBySchool(schoolId, QuestionStatus.ACTIVE, null)
                    .stream().map(Category::id).toList();
        }
        PageResult<Question> page = questionRepository.search(
                schoolId, activeCategoryIds, categoryId, status, query.keyword(),
                query.page(), query.size());
        Map<UUID, String> categoryNames = categoryNames(schoolId);
        return new PageResult<>(
                page.items().stream()
                        .map(question -> QuestionView.of(
                                question,
                                categoryNames.getOrDefault(question.categoryId(), "")))
                        .toList(),
                page.total(),
                page.page(),
                page.size());
    }

    @Override
    @Transactional
    public QuestionView createQuestion(CurrentUser user, CreateQuestionCommand command) {
        CurrentUser manager = M5Access.requireManager(user);
        seedService.ensureLibrary(manager.schoolId());
        UUID schoolId = manager.schoolId().value();
        Category category = requireActiveCategory(schoolId, command.categoryId());
        Question question = Question.create(
                idGenerator.nextId(),
                schoolId,
                category.id(),
                command.stem(),
                command.answerPoints(),
                manager.accountId().value(),
                clockProvider.now());
        questionRepository.save(question);
        return QuestionView.of(question, category.name());
    }

    @Override
    @Transactional
    public QuestionView updateQuestion(
            CurrentUser user, UUID questionId, UpdateQuestionCommand command) {
        CurrentUser manager = M5Access.requireManager(user);
        seedService.ensureLibrary(manager.schoolId());
        UUID schoolId = manager.schoolId().value();
        Question question = requireQuestion(schoolId, questionId);
        UUID targetCategoryId = command.categoryId();
        if (targetCategoryId != null && !targetCategoryId.equals(question.categoryId())) {
            requireActiveCategory(schoolId, targetCategoryId);
        }
        QuestionStatus status = command.status() == null || command.status().isBlank()
                ? null : QuestionStatus.fromWire(command.status());
        question.update(targetCategoryId, command.stem(), command.answerPoints(), status,
                manager.accountId().value(), clockProvider.now());
        questionRepository.save(question);
        String categoryName = categoryRepository
                .findBySchoolAndId(schoolId, question.categoryId())
                .map(Category::name)
                .orElse("");
        return QuestionView.of(question, categoryName);
    }

    @Override
    @Transactional
    public DemoInitView initDemo(CurrentUser user) {
        CurrentUser manager = M5Access.requireManager(user);
        seedService.ensureLibrary(manager.schoolId());
        Library library = libraryRepository.findBySchoolId(manager.schoolId().value())
                .orElseThrow(() -> new DomainException(
                        ErrorCode.INTERNAL_ERROR, "题库初始化失败"));
        return new DemoInitView(true, library.seedVersion());
    }

    private Category requireCategory(UUID schoolId, UUID categoryId) {
        return categoryRepository.findBySchoolAndId(schoolId, categoryId)
                .orElseGet(() -> crossSchoolOrMissing(
                        categoryRepository.findById(categoryId).isPresent(),
                        "类别不存在",
                        Map.of("category_id", categoryId)));
    }

    private Category requireActiveCategory(UUID schoolId, UUID categoryId) {
        Category category = requireCategory(schoolId, categoryId);
        if (!category.isActive()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "类别已停用", Map.of("category_id", categoryId));
        }
        return category;
    }

    private Question requireQuestion(UUID schoolId, UUID questionId) {
        return questionRepository.findBySchoolAndId(schoolId, questionId)
                .orElseGet(() -> crossSchoolOrMissing(
                        questionRepository.findById(questionId).isPresent(),
                        "题目不存在",
                        Map.of("question_id", questionId)));
    }

    /**
     * Distinguishes an id owned by another school (403) from a plain missing id (404).
     * Callers already checked the school-scoped lookup, so an existing row belongs elsewhere.
     */
    private <T> T crossSchoolOrMissing(boolean existsElsewhere, String message,
            Map<String, Object> details) {
        throw new DomainException(
                existsElsewhere ? ErrorCode.CROSS_SCHOOL_ACCESS : ErrorCode.RESOURCE_NOT_FOUND,
                message, details);
    }

    private void requireUniqueName(UUID schoolId, String name, UUID excludedId) {
        boolean duplicated = excludedId == null
                ? categoryRepository.findBySchoolAndName(schoolId, name).isPresent()
                : categoryRepository.existsBySchoolAndNameExcluding(schoolId, name, excludedId);
        if (duplicated) {
            throw duplicateName(name);
        }
    }

    private DomainException duplicateName(String name) {
        return new DomainException(ErrorCode.VALIDATION_ERROR,
                "类别名称已存在", Map.of("field", "name", "value", name));
    }

    private Map<UUID, String> categoryNames(UUID schoolId) {
        return categoryRepository.listBySchool(schoolId, null, null).stream()
                .collect(Collectors.toMap(Category::id, Category::name, (left, right) -> left));
    }

    private UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "参数格式非法", Map.of("field", field));
        }
    }
}
