package com.shijiangtai.m7teacher.infrastructure.persistence.jpa;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinalScoreJpaRepository extends JpaRepository<FinalScoreEntity, UUID> {

    List<FinalScoreEntity> findBySubmissionIdIn(Collection<UUID> submissionIds);

    List<FinalScoreEntity> findByTaskId(UUID taskId);

    List<FinalScoreEntity> findByCourseId(UUID courseId);
}
