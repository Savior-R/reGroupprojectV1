package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import com.shijiangtai.m6training.domain.TrainingEffectiveConfig;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingSessionMeta;
import com.shijiangtai.m6training.domain.TrainingSessionMetaRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrainingSessionMetaRepositoryAdapter implements TrainingSessionMetaRepository {
    private final TrainingSessionMetaJpaRepository repository;
    private final TrainingJsonCodec codec;
    private final JdbcTemplate jdbcTemplate;

    public TrainingSessionMetaRepositoryAdapter(
            TrainingSessionMetaJpaRepository repository,
            TrainingJsonCodec codec,
            JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.codec = codec;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TrainingSessionMeta> findBySessionId(UUID sessionId) {
        return repository.findById(sessionId).map(this::toDomain);
    }

    @Override
    public Map<UUID, TrainingSessionMeta> findBySessionIds(Collection<UUID> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, TrainingSessionMeta> result = new LinkedHashMap<>();
        for (TrainingSessionMetaEntity entity : repository.findBySessionIdIn(sessionIds)) {
            result.put(entity.getSessionId(), toDomain(entity));
        }
        return result;
    }

    @Override
    public TrainingSessionMeta save(TrainingSessionMeta meta) {
        TrainingSessionMetaEntity entity =
                repository.findById(meta.sessionId()).orElseGet(TrainingSessionMetaEntity::new);
        entity.setSessionId(meta.sessionId());
        entity.setTemplateId(meta.templateId());
        entity.setTrainingLevel(meta.trainingLevel().wireValue());
        entity.setEffectiveConfigSnapshot(codec.write(meta.effectiveConfig()));
        entity.setCreatedAt(meta.createdAt());
        repository.save(entity);
        return meta;
    }

    @Override
    public List<UUID> listPreparingSessionIdsByTemplate(UUID templateId) {
        return jdbcTemplate.query("""
                SELECT m.session_id
                FROM m6_training.training_session_meta m
                JOIN m3_classroom.sessions s ON s.id = m.session_id
                WHERE m.template_id = ?
                  AND s.status = 'preparing'
                """,
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                templateId);
    }

    @Override
    public List<TrainingSessionDeadline> listInProgressPastDeadline(Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT m.session_id, s.started_at,
                       (m.effective_config_snapshot -> 'duration' ->> 'max_seconds')::int
                           AS max_seconds
                FROM m6_training.training_session_meta m
                JOIN m3_classroom.sessions s ON s.id = m.session_id
                WHERE s.session_type = 'training'
                  AND s.status = 'in_progress'
                  AND s.started_at IS NOT NULL
                  AND s.started_at + make_interval(secs =>
                        (m.effective_config_snapshot -> 'duration' ->> 'max_seconds')::int)
                      <= ?
                ORDER BY s.started_at ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new TrainingSessionDeadline(
                        rs.getObject("session_id", UUID.class),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getInt("max_seconds")),
                now,
                Math.max(1, limit));
    }

    @Override
    public Optional<UUID> findTemplateIdBySession(UUID sessionId) {
        return repository.findById(sessionId).map(TrainingSessionMetaEntity::getTemplateId);
    }

    @Override
    public void deleteBySessionId(UUID sessionId) {
        repository.deleteById(sessionId);
    }

    private TrainingSessionMeta toDomain(TrainingSessionMetaEntity entity) {
        TrainingEffectiveConfig config =
                codec.read(entity.getEffectiveConfigSnapshot(), TrainingEffectiveConfig.class);
        return new TrainingSessionMeta(
                entity.getSessionId(),
                entity.getTemplateId(),
                TrainingLevel.fromWire(entity.getTrainingLevel()),
                config,
                entity.getCreatedAt());
    }
}
