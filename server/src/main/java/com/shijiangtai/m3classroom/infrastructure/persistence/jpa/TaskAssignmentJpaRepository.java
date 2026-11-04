package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskAssignmentJpaRepository
        extends JpaRepository<TaskAssignmentEntity, UUID> {

    List<TaskAssignmentEntity> findByTaskId(UUID taskId);

    List<TaskAssignmentEntity> findByStudentAccountId(UUID studentAccountId);

    Optional<TaskAssignmentEntity> findByTaskIdAndStudentAccountId(
            UUID taskId, UUID studentAccountId);

    long countByTaskId(UUID taskId);

    long countByTaskIdAndStatus(UUID taskId, String status);

    void deleteByTaskId(UUID taskId);
}
