package com.shijiangtai.m5question.infrastructure.persistence.jpa;

import com.shijiangtai.m5question.domain.Question;
import com.shijiangtai.m5question.domain.QuestionRepository;
import com.shijiangtai.m5question.domain.QuestionStatus;
import com.shijiangtai.shared.domain.PageResult;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

@Repository
public class QuestionRepositoryAdapter implements QuestionRepository {
    private final QuestionJpaRepository repository;

    public QuestionRepositoryAdapter(QuestionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Question> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Question> findBySchoolAndId(UUID schoolId, UUID id) {
        return repository.findBySchoolIdAndId(schoolId, id).map(this::toDomain);
    }

    @Override
    public List<Question> findBySchoolAndIds(UUID schoolId, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return repository.findBySchoolIdAndIdIn(schoolId, ids).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public PageResult<Question> search(
            UUID schoolId,
            List<UUID> categoryIds,
            UUID categoryId,
            QuestionStatus status,
            String keyword,
            int page,
            int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new PageResult<>(List.of(), 0, safePage, safeSize);
        }
        Specification<QuestionEntity> specification =
                (root, query, builder) -> {
                    List<Predicate> predicates = new ArrayList<>();
                    predicates.add(builder.equal(root.get("schoolId"), schoolId));
                    if (categoryIds != null) {
                        predicates.add(root.get("categoryId").in(categoryIds));
                    }
                    if (categoryId != null) {
                        predicates.add(builder.equal(root.get("categoryId"), categoryId));
                    }
                    if (status != null) {
                        predicates.add(builder.equal(root.get("status"), status.wireValue()));
                    }
                    if (keyword != null && !keyword.isBlank()) {
                        String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                        predicates.add(builder.or(
                                builder.like(builder.lower(root.get("stem")), pattern),
                                builder.like(builder.lower(root.get("answerPoints")), pattern)));
                    }
                    return builder.and(predicates.toArray(Predicate[]::new));
                };
        Page<QuestionEntity> result = repository.findAll(
                specification,
                PageRequest.of(
                        safePage - 1,
                        safeSize,
                        Sort.by(Sort.Direction.DESC, "updatedAt")));
        return new PageResult<>(
                result.getContent().stream().map(this::toDomain).toList(),
                result.getTotalElements(),
                safePage,
                safeSize);
    }

    @Override
    public long countByCategory(UUID schoolId, UUID categoryId) {
        return repository.countBySchoolIdAndCategoryId(schoolId, categoryId);
    }

    @Override
    public Question save(Question question) {
        QuestionEntity entity = repository.findById(question.id()).orElseGet(QuestionEntity::new);
        entity.setId(question.id());
        entity.setSchoolId(question.schoolId());
        entity.setCategoryId(question.categoryId());
        entity.setStem(question.stem());
        entity.setAnswerPoints(question.answerPoints());
        entity.setStatus(question.status().wireValue());
        entity.setCreatedBy(question.createdBy());
        entity.setUpdatedBy(question.updatedBy());
        entity.setCreatedAt(question.createdAt());
        entity.setUpdatedAt(question.updatedAt());
        repository.save(entity);
        return question;
    }

    private Question toDomain(QuestionEntity entity) {
        return Question.rehydrate(
                entity.getId(),
                entity.getSchoolId(),
                entity.getCategoryId(),
                entity.getStem(),
                entity.getAnswerPoints(),
                QuestionStatus.fromWire(entity.getStatus()),
                entity.getCreatedBy(),
                entity.getUpdatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
