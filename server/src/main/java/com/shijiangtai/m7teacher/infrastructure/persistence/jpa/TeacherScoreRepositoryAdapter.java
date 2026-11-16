package com.shijiangtai.m7teacher.infrastructure.persistence.jpa;

import com.shijiangtai.m7teacher.domain.ScoringMode;
import com.shijiangtai.m7teacher.domain.TeacherScore;
import com.shijiangtai.m7teacher.domain.TeacherScoreRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class TeacherScoreRepositoryAdapter implements TeacherScoreRepository {

    private final TeacherScoreJpaRepository repository;
    private final M7JsonCodec jsonCodec;

    public TeacherScoreRepositoryAdapter(
            TeacherScoreJpaRepository repository, M7JsonCodec jsonCodec) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Optional<TeacherScore> findBySubmissionId(UUID submissionId) {
        return repository.findBySubmissionId(submissionId).map(this::toDomain);
    }

    @Override
    public Map<UUID, TeacherScore> findBySubmissionIds(Collection<UUID> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, TeacherScore> result = new LinkedHashMap<>();
        for (TeacherScoreEntity entity : repository.findBySubmissionIdIn(submissionIds)) {
            result.put(entity.getSubmissionId(), toDomain(entity));
        }
        return result;
    }

    @Override
    public List<TeacherScore> findByTaskId(UUID taskId) {
        return repository.findByTaskId(taskId).stream().map(this::toDomain).toList();
    }

    @Override
    public long countByTaskId(UUID taskId) {
        return repository.countByTaskId(taskId);
    }

    @Override
    public TeacherScore insert(TeacherScore score) {
        repository.saveAndFlush(toEntity(score));
        return score;
    }

    @Override
    public boolean updateIfVersion(TeacherScore score, int expectedVersion) {
        return repository.updateIfVersion(
                score.submissionId(),
                expectedVersion,
                score.scoringMode().wireValue(),
                jsonCodec.writeDimensions(score.dimensionScores()),
                score.teacherTotal(),
                score.comment(),
                score.gradedBy(),
                score.gradedAt(),
                score.updatedAt()) == 1;
    }

    @Override
    public int deleteByTaskId(UUID taskId) {
        return repository.deleteByTaskId(taskId);
    }

    private TeacherScoreEntity toEntity(TeacherScore score) {
        TeacherScoreEntity entity = new TeacherScoreEntity();
        entity.setId(score.id());
        entity.setSubmissionId(score.submissionId());
        entity.setTaskId(score.taskId());
        entity.setCourseId(score.courseId());
        entity.setStudentAccountId(score.studentAccountId());
        entity.setClassIdSnapshot(score.classIdSnapshot());
        entity.setScoringMode(score.scoringMode().wireValue());
        entity.setDimensionScores(jsonCodec.writeDimensions(score.dimensionScores()));
        entity.setTeacherTotal(score.teacherTotal());
        entity.setComment(score.comment());
        entity.setVersion(score.version());
        entity.setGradedBy(score.gradedBy());
        entity.setGradedAt(score.gradedAt());
        entity.setUpdatedAt(score.updatedAt());
        return entity;
    }

    private TeacherScore toDomain(TeacherScoreEntity entity) {
        return new TeacherScore(
                entity.getId(),
                entity.getSubmissionId(),
                entity.getTaskId(),
                entity.getCourseId(),
                entity.getStudentAccountId(),
                entity.getClassIdSnapshot(),
                ScoringMode.fromWire(entity.getScoringMode()),
                jsonCodec.readDimensions(entity.getDimensionScores()),
                entity.getTeacherTotal(),
                entity.getComment(),
                entity.getVersion(),
                entity.getGradedBy(),
                entity.getGradedAt(),
                entity.getUpdatedAt());
    }
}
