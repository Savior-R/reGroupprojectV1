package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskJpaRepository extends JpaRepository<TaskEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskEntity t where t.id = :id")
    Optional<TaskEntity> findByIdForUpdate(@Param("id") UUID id);

    List<TaskEntity> findByCourseIdOrderByUpdatedAtDesc(UUID courseId);
}
