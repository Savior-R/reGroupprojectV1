package com.shijiangtai.m5question.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryJpaRepository extends JpaRepository<CategoryEntity, UUID> {

    Optional<CategoryEntity> findBySchoolIdAndId(UUID schoolId, UUID id);

    Optional<CategoryEntity> findBySchoolIdAndName(UUID schoolId, String name);

    boolean existsBySchoolIdAndNameAndIdNot(UUID schoolId, String name, UUID id);

    List<CategoryEntity> findBySchoolIdOrderBySortOrderAscNameAsc(UUID schoolId);

    @Query("select coalesce(max(c.sortOrder), 0) from CategoryEntity c where c.schoolId = :schoolId")
    int maxSortOrder(@Param("schoolId") UUID schoolId);
}
