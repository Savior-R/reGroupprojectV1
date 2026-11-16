package com.shijiangtai.m7teacher.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface TeacherScoreRepository {

    Optional<TeacherScore> findBySubmissionId(UUID submissionId);

    Map<UUID, TeacherScore> findBySubmissionIds(Collection<UUID> submissionIds);

    List<TeacherScore> findByTaskId(UUID taskId);

    long countByTaskId(UUID taskId);

    TeacherScore insert(TeacherScore score);

    /**
     * Conditional update by submission id and expected version.
     *
     * @return true when one row was updated; false means a version conflict
     */
    boolean updateIfVersion(TeacherScore score, int expectedVersion);

    int deleteByTaskId(UUID taskId);
}
