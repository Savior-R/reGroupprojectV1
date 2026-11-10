package com.shijiangtai.m5question.infrastructure.persistence.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryJpaRepository extends JpaRepository<LibraryEntity, UUID> {

    Optional<LibraryEntity> findBySchoolId(UUID schoolId);
}
