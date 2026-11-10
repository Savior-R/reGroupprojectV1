package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import com.shijiangtai.m6training.domain.TrainingRecord;
import com.shijiangtai.m6training.domain.TrainingRecordRepository;
import com.shijiangtai.m6training.domain.TrainingRecordStatus;
import com.shijiangtai.shared.domain.PageResult;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrainingRecordRepositoryAdapter implements TrainingRecordRepository {
    private static final List<String> ACTIVE = List.of(
            TrainingRecordStatus.SELECTED.wireValue(),
            TrainingRecordStatus.EVALUATED.wireValue());

    private final TrainingRecordJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public TrainingRecordRepositoryAdapter(
            TrainingRecordJpaRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TrainingRecord> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<TrainingRecord> findBySessionId(UUID sessionId) {
        return repository.findByTrainingSessionId(sessionId).map(this::toDomain);
    }

    @Override
    public PageResult<TrainingRecord> listByStudent(
            UUID studentAccountId, TrainingRecordStatus status, Instant now, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Specification<TrainingRecordEntity> specification =
                (root, query, builder) -> {
                    List<Predicate> predicates = new ArrayList<>();
                    predicates.add(builder.equal(root.get("studentAccountId"), studentAccountId));
                    if (status == null) {
                        predicates.add(builder.notEqual(
                                root.get("status"), TrainingRecordStatus.DELETED.wireValue()));
                    } else if (status == TrainingRecordStatus.DELETED) {
                        predicates.add(builder.equal(
                                root.get("status"), TrainingRecordStatus.DELETED.wireValue()));
                    } else if (status == TrainingRecordStatus.EXPIRED) {
                        Predicate expiredStatus = builder.equal(
                                root.get("status"), TrainingRecordStatus.EXPIRED.wireValue());
                        Predicate overdue = builder.and(
                                root.get("status").in(ACTIVE),
                                builder.lessThanOrEqualTo(root.get("expiresAt"), now));
                        predicates.add(builder.or(expiredStatus, overdue));
                    } else {
                        predicates.add(builder.equal(root.get("status"), status.wireValue()));
                        predicates.add(builder.greaterThan(root.get("expiresAt"), now));
                    }
                    return builder.and(predicates.toArray(Predicate[]::new));
                };
        Page<TrainingRecordEntity> result = repository.findAll(
                specification,
                PageRequest.of(safePage - 1, safeSize,
                        Sort.by(Sort.Direction.DESC, "selectedAt")));
        return new PageResult<>(
                result.getContent().stream().map(this::toDomain).toList(),
                result.getTotalElements(),
                safePage,
                safeSize);
    }

    @Override
    public List<TrainingRecord> listActiveByGroup(UUID studentAccountId, UUID templateId) {
        List<TrainingRecordEntity> entities = templateId == null
                ? repository.findByStudentAccountIdAndStatusInOrderBySelectedAtAsc(
                        studentAccountId, ACTIVE).stream()
                        .filter(entity -> entity.getTemplateId() == null)
                        .toList()
                : repository
                        .findByStudentAccountIdAndTemplateIdAndStatusInOrderBySelectedAtAsc(
                                studentAccountId, templateId, ACTIVE);
        return entities.stream().map(this::toDomain).toList();
    }

    @Override
    public void lockGroup(UUID studentAccountId, UUID templateId) {
        String key = studentAccountId + ":" + (templateId == null ? "free" : templateId);
        jdbcTemplate.queryForList(
                "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)", key);
    }

    @Override
    public List<TrainingRecord> listActiveExpired(Instant now, int limit) {
        return repository.findByStatusInAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                        ACTIVE, now, PageRequest.of(0, Math.max(1, limit))).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<TrainingRecord> listPendingDeletion(int limit) {
        return repository.findByStatusInOrderByUpdatedAtAsc(
                        List.of(TrainingRecordStatus.DELETED.wireValue(),
                                TrainingRecordStatus.EXPIRED.wireValue()),
                        PageRequest.of(0, Math.max(1, limit))).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public TrainingRecord save(TrainingRecord record) {
        TrainingRecordEntity entity =
                repository.findById(record.id()).orElseGet(TrainingRecordEntity::new);
        entity.setId(record.id());
        entity.setTrainingSessionId(record.trainingSessionId());
        entity.setStudentAccountId(record.studentAccountId());
        entity.setTemplateId(record.templateId());
        entity.setSelectedAt(record.selectedAt());
        entity.setExpiresAt(record.expiresAt());
        entity.setStatus(record.status().wireValue());
        entity.setDeletedAt(record.deletedAt());
        entity.setCreatedAt(record.createdAt());
        entity.setUpdatedAt(record.updatedAt());
        repository.saveAndFlush(entity);
        return record;
    }

    @Override
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    private TrainingRecord toDomain(TrainingRecordEntity entity) {
        return TrainingRecord.rehydrate(
                entity.getId(),
                entity.getTrainingSessionId(),
                entity.getStudentAccountId(),
                entity.getTemplateId(),
                entity.getSelectedAt(),
                entity.getExpiresAt(),
                TrainingRecordStatus.fromWire(entity.getStatus()),
                entity.getDeletedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
