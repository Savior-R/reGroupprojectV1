package com.shijiangtai.m7teacher.infrastructure.persistence.jpa;

import com.shijiangtai.m7teacher.domain.GradingConfig;
import com.shijiangtai.m7teacher.domain.GradingConfigRepository;
import com.shijiangtai.m7teacher.domain.ScoringMode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class GradingConfigRepositoryAdapter implements GradingConfigRepository {

    private final GradingConfigJpaRepository repository;

    public GradingConfigRepositoryAdapter(GradingConfigJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<GradingConfig> findByTaskId(UUID taskId) {
        return repository.findByTaskId(taskId).map(this::toDomain);
    }

    @Override
    public GradingConfig insert(GradingConfig config) {
        repository.saveAndFlush(toEntity(config));
        return config;
    }

    @Override
    public Optional<GradingConfig> insertIfAbsent(GradingConfig config) {
        repository.insertIfAbsent(
                config.id(),
                config.taskId(),
                config.courseId(),
                config.aiWeight(),
                config.teacherWeight(),
                config.scoringMode().wireValue(),
                config.configVersion(),
                config.lockedAt(),
                config.updatedBy(),
                config.createdAt(),
                config.updatedAt());
        return repository.findByTaskId(config.taskId()).map(this::toDomain);
    }

    @Override
    public GradingConfig update(GradingConfig config) {
        repository.saveAndFlush(toEntity(config));
        return config;
    }

    private GradingConfigEntity toEntity(GradingConfig config) {
        GradingConfigEntity entity = new GradingConfigEntity();
        entity.setId(config.id());
        entity.setTaskId(config.taskId());
        entity.setCourseId(config.courseId());
        entity.setAiWeight(config.aiWeight());
        entity.setTeacherWeight(config.teacherWeight());
        entity.setScoringMode(config.scoringMode().wireValue());
        entity.setConfigVersion(config.configVersion());
        entity.setLockedAt(config.lockedAt());
        entity.setUpdatedBy(config.updatedBy());
        entity.setCreatedAt(config.createdAt());
        entity.setUpdatedAt(config.updatedAt());
        return entity;
    }

    private GradingConfig toDomain(GradingConfigEntity entity) {
        return new GradingConfig(
                entity.getId(),
                entity.getTaskId(),
                entity.getCourseId(),
                entity.getAiWeight(),
                entity.getTeacherWeight(),
                ScoringMode.fromWire(entity.getScoringMode()),
                entity.getConfigVersion(),
                entity.getLockedAt(),
                entity.getUpdatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
