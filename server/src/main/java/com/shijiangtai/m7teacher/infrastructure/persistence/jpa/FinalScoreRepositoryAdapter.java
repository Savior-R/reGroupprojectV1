package com.shijiangtai.m7teacher.infrastructure.persistence.jpa;

import com.shijiangtai.m7teacher.domain.FinalScore;
import com.shijiangtai.m7teacher.domain.FinalScoreRepository;
import com.shijiangtai.m7teacher.domain.FinalScoreStatus;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class FinalScoreRepositoryAdapter implements FinalScoreRepository {

    private final FinalScoreJpaRepository repository;
    private final M7JsonCodec jsonCodec;

    public FinalScoreRepositoryAdapter(
            FinalScoreJpaRepository repository, M7JsonCodec jsonCodec) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Optional<FinalScore> findBySubmissionId(UUID submissionId) {
        return repository.findById(submissionId).map(this::toDomain);
    }

    @Override
    public Map<UUID, FinalScore> findBySubmissionIds(Collection<UUID> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, FinalScore> result = new LinkedHashMap<>();
        for (FinalScoreEntity entity : repository.findBySubmissionIdIn(submissionIds)) {
            result.put(entity.getSubmissionId(), toDomain(entity));
        }
        return result;
    }

    @Override
    public List<FinalScore> findByTaskId(UUID taskId) {
        return repository.findByTaskId(taskId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<FinalScore> findByCourseId(UUID courseId) {
        return repository.findByCourseId(courseId).stream().map(this::toDomain).toList();
    }

    @Override
    public FinalScore upsert(FinalScore score) {
        FinalScoreEntity entity = repository.findById(score.submissionId())
                .orElseGet(FinalScoreEntity::new);
        entity.setSubmissionId(score.submissionId());
        entity.setTaskId(score.taskId());
        entity.setCourseId(score.courseId());
        entity.setStudentAccountId(score.studentAccountId());
        entity.setAiEvaluationId(score.aiEvaluationId());
        entity.setTeacherScoreId(score.teacherScoreId());
        entity.setAiTotal(score.aiTotal());
        entity.setTeacherTotal(score.teacherTotal());
        entity.setFinalTotal(score.finalTotal());
        entity.setFinalDimensions(score.status() == FinalScoreStatus.COMPLETED
                ? jsonCodec.writeDimensions(score.finalDimensions())
                : null);
        entity.setStatus(score.status().wireValue());
        entity.setClassIdSnapshot(score.classIdSnapshot());
        entity.setConfigVersion(score.configVersion());
        entity.setErrorCode(score.errorCode());
        entity.setErrorMessage(score.errorMessage());
        entity.setComputedAt(score.computedAt());
        repository.saveAndFlush(entity);
        return score;
    }

    private FinalScore toDomain(FinalScoreEntity entity) {
        return new FinalScore(
                entity.getSubmissionId(),
                entity.getTaskId(),
                entity.getCourseId(),
                entity.getStudentAccountId(),
                entity.getAiEvaluationId(),
                entity.getTeacherScoreId(),
                entity.getAiTotal(),
                entity.getTeacherTotal(),
                entity.getFinalTotal(),
                jsonCodec.readDimensions(entity.getFinalDimensions()),
                FinalScoreStatus.fromWire(entity.getStatus()),
                entity.getClassIdSnapshot(),
                entity.getConfigVersion(),
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getComputedAt());
    }
}
