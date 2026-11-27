package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import com.shijiangtai.m6training.domain.TrainingConfigSnapshot;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingOwnerRole;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingTemplateRepository;
import com.shijiangtai.m6training.domain.TrainingTemplateSourceType;
import com.shijiangtai.m6training.domain.TrainingTemplateStatus;
import com.shijiangtai.shared.domain.PageResult;
import jakarta.persistence.criteria.Predicate;
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
public class TrainingTemplateRepositoryAdapter implements TrainingTemplateRepository {
    private final TrainingTemplateJpaRepository repository;
    private final TrainingJsonCodec codec;
    private final JdbcTemplate jdbcTemplate;

    public TrainingTemplateRepositoryAdapter(
            TrainingTemplateJpaRepository repository,
            TrainingJsonCodec codec,
            JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.codec = codec;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TrainingTemplate> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<TrainingTemplate> findByIdAndOwner(UUID id, UUID ownerAccountId) {
        return repository.findByIdAndOwnerAccountId(id, ownerAccountId).map(this::toDomain);
    }

    @Override
    public PageResult<TrainingTemplate> listByOwner(
            UUID ownerAccountId,
            TrainingOwnerRole ownerRole,
            TrainingLevel trainingLevel,
            TrainingTemplateStatus status,
            int page,
            int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Specification<TrainingTemplateEntity> specification =
                (root, query, builder) -> {
                    List<Predicate> predicates = new ArrayList<>();
                    predicates.add(builder.equal(root.get("ownerAccountId"), ownerAccountId));
                    if (ownerRole != null) {
                        predicates.add(builder.equal(
                                root.get("ownerRole"), ownerRole.wireValue()));
                    }
                    if (trainingLevel != null) {
                        predicates.add(builder.equal(
                                root.get("trainingLevel"), trainingLevel.wireValue()));
                    }
                    predicates.add(builder.equal(
                            root.get("status"),
                            (status == null ? TrainingTemplateStatus.ACTIVE : status)
                                    .wireValue()));
                    return builder.and(predicates.toArray(Predicate[]::new));
                };
        Page<TrainingTemplateEntity> result = repository.findAll(
                specification,
                PageRequest.of(safePage - 1, safeSize,
                        Sort.by(Sort.Direction.DESC, "updatedAt")));
        return new PageResult<>(
                result.getContent().stream().map(this::toDomain).toList(),
                result.getTotalElements(),
                safePage,
                safeSize);
    }

    @Override
    public List<TrainingTemplate> listActiveByOwner(UUID ownerAccountId) {
        return repository.findByOwnerAccountIdAndStatusOrderByUpdatedAtDesc(
                        ownerAccountId, TrainingTemplateStatus.ACTIVE.wireValue()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countActiveByOwner(UUID ownerAccountId) {
        return repository.countByOwnerAccountIdAndStatus(
                ownerAccountId, TrainingTemplateStatus.ACTIVE.wireValue());
    }

    @Override
    public boolean existsActiveCopy(UUID ownerAccountId, UUID sourcePublicationId) {
        return repository.existsByOwnerAccountIdAndSourcePublicationIdAndStatus(
                ownerAccountId,
                sourcePublicationId,
                TrainingTemplateStatus.ACTIVE.wireValue());
    }

    @Override
    public void lockOwner(UUID ownerAccountId) {
        jdbcTemplate.queryForList(
                "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)",
                "m6-template-quota:" + ownerAccountId);
    }

    @Override
    public TrainingTemplate save(TrainingTemplate template) {
        TrainingTemplateEntity entity =
                repository.findById(template.id()).orElseGet(TrainingTemplateEntity::new);
        entity.setId(template.id());
        entity.setSchoolId(template.schoolId());
        entity.setOwnerAccountId(template.ownerAccountId());
        entity.setOwnerRole(template.ownerRole().wireValue());
        entity.setName(template.name());
        entity.setTrainingLevel(template.trainingLevel().wireValue());
        entity.setClassroomMode(template.classroomMode());
        entity.setSourceType(template.sourceType().wireValue());
        entity.setSourceTaskId(template.sourceTaskId());
        entity.setSourceTaskConfigVersion(template.sourceTaskConfigVersion());
        entity.setSourceTemplateId(template.sourceTemplateId());
        entity.setSourcePublicationId(template.sourcePublicationId());
        entity.setConfigSnapshot(codec.write(template.config()));
        entity.setStatus(template.status().wireValue());
        entity.setArchivedAt(template.archivedAt());
        entity.setCreatedAt(template.createdAt());
        entity.setUpdatedAt(template.updatedAt());
        repository.saveAndFlush(entity);
        return template;
    }

    private TrainingTemplate toDomain(TrainingTemplateEntity entity) {
        TrainingConfigSnapshot config =
                codec.read(entity.getConfigSnapshot(), TrainingConfigSnapshot.class);
        TrainingLevel level = TrainingLevel.fromWire(entity.getTrainingLevel());
        return TrainingTemplate.rehydrate(
                entity.getId(),
                entity.getSchoolId(),
                entity.getOwnerAccountId(),
                TrainingOwnerRole.fromWire(entity.getOwnerRole()),
                entity.getName(),
                level,
                config,
                TrainingTemplateSourceType.fromWire(entity.getSourceType()),
                entity.getSourceTaskId(),
                entity.getSourceTaskConfigVersion(),
                entity.getSourceTemplateId(),
                entity.getSourcePublicationId(),
                TrainingTemplateStatus.fromWire(entity.getStatus()),
                entity.getArchivedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
