package com.shijiangtai.m5question.domain;

import com.shijiangtai.shared.domain.PageResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for questions. */
public interface QuestionRepository {

    Optional<Question> findById(UUID id);

    Optional<Question> findBySchoolAndId(UUID schoolId, UUID id);

    List<Question> findBySchoolAndIds(UUID schoolId, List<UUID> ids);

    PageResult<Question> search(
            UUID schoolId,
            List<UUID> categoryIds,
            UUID categoryId,
            QuestionStatus status,
            String keyword,
            int page,
            int size);

    long countByCategory(UUID schoolId, UUID categoryId);

    Question save(Question question);
}
