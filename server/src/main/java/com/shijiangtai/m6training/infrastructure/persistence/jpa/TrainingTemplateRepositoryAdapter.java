package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import com.shijiangtai.m6training.domain.TrainingConfigSnapshot;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingTemplateRepository;
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
    public Optional<TrainingTemplate> findByIdAndCreator(UUID id, UUID creatorId) {
        return repository.findByIdAndCreatedBy(id, creatorId).map(this::toDomain);
    }

    @Override
    public boolean existsPublished(UUID sourceTaskId, UUID courseId, TrainingLevel trainingLevel) {
        return repository.existsBySourceTaskIdAndCourseIdAndTrainingLevelAndStatus(
                sourceTaskId, courseId, trainingLevel.wireValue(),
                TrainingTemplateStatus.PUBLISHED.wireValue());
    }

    @Override
    public PageResult<TrainingTemplate> listByCreator(
            UUID creatorId, TrainingLevel trainingLevel, UUID courseId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Specification<TrainingTemplateEntity> specification =
                (root, query, builder) -> {
                    List<Predicate> predicates = new ArrayList<>();
                    predicates.add(builder.equal(root.get("createdBy"), creatorId));
                    if (trainingLevel != null) {
                        predicates.add(builder.equal(
                                root.get("trainingLevel"), trainingLevel.wireValue()));
                    }
                    if (courseId != null) {
                        predicates.add(builder.equal(root.get("courseId"), courseId));
                    }
                    return builder.and(predicates.toArray(Predicate[]::new));
                };
        Page<TrainingTemplateEntity> result = repository.findAll(
                specification,
                PageRequest.of(safePage - 1, safeSize,
                        Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResult<>(
                result.getContent().stream().map(this::toDomain).toList(),
                result.getTotalElements(),
                safePage,
                safeSize);
    }

    @Override
    public PageResult<UUID> listStudentVisibleIds(
            UUID studentAccountId, UUID schoolId, TrainingLevel trainingLevel,
            UUID courseId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        StringBuilder from = new StringBuilder("""
                FROM m6_training.training_templates t
                JOIN m2_course.courses c ON c.id = t.course_id
                JOIN m2_course.course_students cs
                  ON cs.course_id = t.course_id
                 AND cs.student_account_id = ?
                 AND cs.status = 'active'
                WHERE t.status = 'published'
                  AND c.school_id = ?
                  AND c.status = 'active'
                  AND (c.end_at IS NULL OR c.end_at > now())
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(studentAccountId);
        parameters.add(schoolId);
        if (trainingLevel != null) {
            from.append(" AND t.training_level = ?\n");
            parameters.add(trainingLevel.wireValue());
        }
        if (courseId != null) {
            from.append(" AND t.course_id = ?\n");
            parameters.add(courseId);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) " + from, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(safeSize);
        pageParameters.add((long) (safePage - 1) * safeSize);
        List<UUID> ids = jdbcTemplate.query(
                "SELECT t.id " + from + " ORDER BY t.published_at DESC NULLS LAST, t.created_at DESC"
                        + " LIMIT ? OFFSET ?",
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                pageParameters.toArray());
        return new PageResult<>(ids, total == null ? 0 : total, safePage, safeSize);
    }

    @Override
    public List<TrainingTemplate> listPublished(int limit) {
        return repository.findByStatusOrderByUpdatedAtAsc(
                        TrainingTemplateStatus.PUBLISHED.wireValue(),
                        PageRequest.of(0, Math.max(1, limit))).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public TrainingTemplate save(TrainingTemplate template) {
        TrainingTemplateEntity entity =
                repository.findById(template.id()).orElseGet(TrainingTemplateEntity::new);
        entity.setId(template.id());
        entity.setSourceTaskId(template.sourceTaskId());
        entity.setSourceTaskConfigVersion(template.sourceTaskConfigVersion());
        entity.setCourseId(template.courseId());
        entity.setTrainingLevel(template.trainingLevel().wireValue());
        entity.setClassroomMode(template.classroomMode());
        entity.setConfigSnapshot(codec.write(template.config()));
        entity.setCreatedBy(template.createdBy());
        entity.setStatus(template.status().wireValue());
        entity.setPublishedAt(template.publishedAt());
        entity.setUnpublishedAt(template.unpublishedAt());
        entity.setCreatedAt(template.createdAt());
        entity.setUpdatedAt(template.updatedAt());
        repository.saveAndFlush(entity);
        return template;
    }

    private TrainingTemplate toDomain(TrainingTemplateEntity entity) {
        TrainingConfigSnapshot config =
                codec.read(entity.getConfigSnapshot(), TrainingConfigSnapshot.class);
        return TrainingTemplate.rehydrate(
                entity.getId(),
                entity.getSourceTaskId(),
                entity.getSourceTaskConfigVersion(),
                entity.getCourseId(),
                TrainingLevel.fromWire(entity.getTrainingLevel()),
                entity.getClassroomMode(),
                config,
                entity.getCreatedBy(),
                TrainingTemplateStatus.fromWire(entity.getStatus()),
                entity.getPublishedAt(),
                entity.getUnpublishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
