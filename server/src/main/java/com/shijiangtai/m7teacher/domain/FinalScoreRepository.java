package com.shijiangtai.m7teacher.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface FinalScoreRepository {

    Optional<FinalScore> findBySubmissionId(UUID submissionId);

    Map<UUID, FinalScore> findBySubmissionIds(Collection<UUID> submissionIds);

    List<FinalScore> findByTaskId(UUID taskId);

    List<FinalScore> findByCourseId(UUID courseId);

    FinalScore upsert(FinalScore score);
}
