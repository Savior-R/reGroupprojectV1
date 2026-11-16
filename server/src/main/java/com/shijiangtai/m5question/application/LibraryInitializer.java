package com.shijiangtai.m5question.application;

import com.shijiangtai.m5question.domain.Category;
import com.shijiangtai.m5question.domain.CategoryRepository;
import com.shijiangtai.m5question.domain.Library;
import com.shijiangtai.m5question.domain.LibraryRepository;
import com.shijiangtai.m5question.domain.Question;
import com.shijiangtai.m5question.domain.QuestionRepository;
import com.shijiangtai.m5question.domain.SeedCatalog;
import com.shijiangtai.m5question.infrastructure.seed.SeedCatalogLoader;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a school library and copies the seed catalog in one transaction.
 *
 * <p>Kept in its own bean so {@link SeedServiceImpl} can catch the unique-constraint
 * violation of a concurrent initializer after the transaction has rolled back.
 */
@Component
public class LibraryInitializer {
    private static final Logger log = LoggerFactory.getLogger(LibraryInitializer.class);

    private final LibraryRepository libraryRepository;
    private final CategoryRepository categoryRepository;
    private final QuestionRepository questionRepository;
    private final SeedCatalogLoader seedCatalogLoader;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;

    public LibraryInitializer(
            LibraryRepository libraryRepository,
            CategoryRepository categoryRepository,
            QuestionRepository questionRepository,
            SeedCatalogLoader seedCatalogLoader,
            IdGenerator idGenerator,
            ClockProvider clockProvider) {
        this.libraryRepository = libraryRepository;
        this.categoryRepository = categoryRepository;
        this.questionRepository = questionRepository;
        this.seedCatalogLoader = seedCatalogLoader;
        this.idGenerator = idGenerator;
        this.clockProvider = clockProvider;
    }

    @Transactional
    public boolean initialize(SchoolId schoolId) {
        if (libraryRepository.findBySchoolId(schoolId.value()).isPresent()) {
            return false;
        }
        SeedCatalog catalog = seedCatalogLoader.load();
        Instant now = clockProvider.now();
        libraryRepository.save(Library.create(
                idGenerator.nextId(), schoolId.value(), catalog.seedVersion(), now));

        Map<String, UUID> categoryIds = new LinkedHashMap<>();
        for (SeedCatalog.SeedCategory seedCategory : catalog.categories()) {
            if (seedCategory.name() == null || seedCategory.name().isBlank()) {
                log.warn("Skip seed category without name for school {}", schoolId.value());
                continue;
            }
            String name = seedCategory.name().trim();
            Optional<Category> existing = categoryRepository.findBySchoolAndName(schoolId.value(), name);
            UUID categoryId;
            if (existing.isPresent()) {
                categoryId = existing.get().id();
            } else {
                Category category = Category.create(
                        idGenerator.nextId(), schoolId.value(), name,
                        seedCategory.sortOrder(), null, now);
                categoryRepository.save(category);
                categoryId = category.id();
            }
            categoryIds.put(name, categoryId);
        }

        int copied = 0;
        for (SeedCatalog.SeedQuestion seedQuestion : catalog.questions()) {
            if (seedQuestion.stem() == null || seedQuestion.stem().isBlank()
                    || seedQuestion.answerPoints() == null
                    || seedQuestion.answerPoints().isBlank()) {
                log.warn("Skip seed question without stem/answer points for school {}",
                        schoolId.value());
                continue;
            }
            UUID categoryId = categoryIds.get(seedQuestion.category());
            if (categoryId == null) {
                log.warn("Skip seed question with unknown category {} for school {}",
                        seedQuestion.category(), schoolId.value());
                continue;
            }
            questionRepository.save(Question.create(
                    idGenerator.nextId(),
                    schoolId.value(),
                    categoryId,
                    seedQuestion.stem(),
                    seedQuestion.answerPoints(),
                    null,
                    now));
            copied++;
        }
        log.info("Initialized question bank for school {} with seed {} ({} questions)",
                schoolId.value(), catalog.seedVersion(), copied);
        return true;
    }
}
