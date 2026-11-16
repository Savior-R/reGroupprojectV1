package com.shijiangtai.m5question.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for categories. */
public interface CategoryRepository {

    Optional<Category> findById(UUID id);

    Optional<Category> findBySchoolAndId(UUID schoolId, UUID id);

    Optional<Category> findBySchoolAndName(UUID schoolId, String name);

    List<Category> listBySchool(UUID schoolId, QuestionStatus status, String keyword);

    int nextSortOrder(UUID schoolId);

    boolean existsBySchoolAndNameExcluding(UUID schoolId, String name, UUID excludedId);

    Category save(Category category);
}
