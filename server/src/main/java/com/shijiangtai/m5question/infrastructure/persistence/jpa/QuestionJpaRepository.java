package com.shijiangtai.m5question.infrastructure.persistence.jpa;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface QuestionJpaRepository
        extends JpaRepository<QuestionEntity, UUID>, JpaSpecificationExecutor<QuestionEntity> {

    Optional<QuestionEntity> findBySchoolIdAndId(UUID schoolId, UUID id);

    List<QuestionEntity> findBySchoolIdAndIdIn(UUID schoolId, Collection<UUID> ids);

    long countBySchoolIdAndCategoryId(UUID schoolId, UUID categoryId);
}
