package com.shijiangtai.m5question.infrastructure.persistence.jpa;

import com.shijiangtai.m5question.domain.Category;
import com.shijiangtai.m5question.domain.CategoryRepository;
import com.shijiangtai.m5question.domain.QuestionStatus;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryRepositoryAdapter implements CategoryRepository {
    private final CategoryJpaRepository repository;

    public CategoryRepositoryAdapter(CategoryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Category> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Category> findBySchoolAndId(UUID schoolId, UUID id) {
        return repository.findBySchoolIdAndId(schoolId, id).map(this::toDomain);
    }

    @Override
    public Optional<Category> findBySchoolAndName(UUID schoolId, String name) {
        return repository.findBySchoolIdAndName(schoolId, name).map(this::toDomain);
    }

    @Override
    public List<Category> listBySchool(UUID schoolId, QuestionStatus status, String keyword) {
        String needle = normalize(keyword);
        return repository.findBySchoolIdOrderBySortOrderAscNameAsc(schoolId).stream()
                .filter(entity -> status == null || entity.getStatus().equals(status.wireValue()))
                .filter(entity -> needle == null
                        || entity.getName().toLowerCase(Locale.ROOT).contains(needle))
                .map(this::toDomain)
                .toList();
    }

    @Override
    public int nextSortOrder(UUID schoolId) {
        return repository.maxSortOrder(schoolId) + 1;
    }

    @Override
    public boolean existsBySchoolAndNameExcluding(UUID schoolId, String name, UUID excludedId) {
        return repository.existsBySchoolIdAndNameAndIdNot(schoolId, name, excludedId);
    }

    @Override
    public Category save(Category category) {
        CategoryEntity entity = repository.findById(category.id()).orElseGet(CategoryEntity::new);
        entity.setId(category.id());
        entity.setSchoolId(category.schoolId());
        entity.setName(category.name());
        entity.setSortOrder(category.sortOrder());
        entity.setStatus(category.status().wireValue());
        entity.setCreatedBy(category.createdBy());
        entity.setUpdatedBy(category.updatedBy());
        entity.setCreatedAt(category.createdAt());
        entity.setUpdatedAt(category.updatedAt());
        repository.save(entity);
        return category;
    }

    private Category toDomain(CategoryEntity entity) {
        return Category.rehydrate(
                entity.getId(),
                entity.getSchoolId(),
                entity.getName(),
                entity.getSortOrder(),
                QuestionStatus.fromWire(entity.getStatus()),
                entity.getCreatedBy(),
                entity.getUpdatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
