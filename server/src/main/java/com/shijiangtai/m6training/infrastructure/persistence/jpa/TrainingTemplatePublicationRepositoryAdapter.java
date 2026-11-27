package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingPublicationStatus;
import com.shijiangtai.m6training.domain.TrainingTemplatePublication;
import com.shijiangtai.m6training.domain.TrainingTemplatePublicationRepository;
import com.shijiangtai.m6training.domain.TrainingTemplateSourceType;
import com.shijiangtai.m6training.domain.TrainingUnpublishReason;
import com.shijiangtai.shared.domain.PageResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrainingTemplatePublicationRepositoryAdapter
        implements TrainingTemplatePublicationRepository {
    private static final String SELECT_COLUMNS = """
            SELECT p.id, p.template_id, t.name AS template_name, t.training_level,
                   COALESCE(t.config_snapshot ->> 'topic', '') AS topic,
                   t.source_type, p.course_id, c.name AS course_name, p.status,
                   p.published_at, p.unpublished_at, p.unpublish_reason,
                   COALESCE((t.config_snapshot -> 'duration' ->> 'max_seconds')::int, 0)
                       AS max_duration_seconds,
                   COALESCE(jsonb_array_length(t.config_snapshot -> 'virtual_students'), 0)
                       AS virtual_student_count,
            """;

    private final TrainingTemplatePublicationJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public TrainingTemplatePublicationRepositoryAdapter(
            TrainingTemplatePublicationJpaRepository repository,
            JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TrainingTemplatePublication> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<TrainingTemplatePublication> findPublished(UUID templateId, UUID courseId) {
        return repository.findFirstByTemplateIdAndCourseIdAndStatus(
                        templateId, courseId, TrainingPublicationStatus.PUBLISHED.wireValue())
                .map(this::toDomain);
    }

    @Override
    public List<TrainingTemplatePublication> listByTemplate(UUID templateId) {
        return repository.findByTemplateIdOrderByPublishedAtDesc(templateId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<TrainingTemplatePublication> listPublishedByTemplate(UUID templateId) {
        return repository.findByTemplateIdAndStatusOrderByPublishedAtDesc(
                        templateId, TrainingPublicationStatus.PUBLISHED.wireValue()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<TrainingTemplatePublication> listPublished(int limit) {
        return repository.findByStatusOrderByPublishedAtAsc(
                        TrainingPublicationStatus.PUBLISHED.wireValue(),
                        PageRequest.of(0, Math.max(1, limit))).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public PageResult<PublicationRow> listByOwner(
            UUID ownerAccountId,
            UUID templateId,
            UUID courseId,
            TrainingPublicationStatus status,
            int page,
            int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        StringBuilder from = new StringBuilder("""
                FROM m6_training.training_template_publications p
                JOIN m6_training.training_templates t ON t.id = p.template_id
                JOIN m2_course.courses c ON c.id = p.course_id
                WHERE t.owner_account_id = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(ownerAccountId);
        if (templateId != null) {
            from.append(" AND p.template_id = ?\n");
            parameters.add(templateId);
        }
        if (courseId != null) {
            from.append(" AND p.course_id = ?\n");
            parameters.add(courseId);
        }
        if (status != null) {
            from.append(" AND p.status = ?\n");
            parameters.add(status.wireValue());
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) " + from, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(safeSize);
        pageParameters.add((long) (safePage - 1) * safeSize);
        List<PublicationRow> rows = jdbcTemplate.query(
                SELECT_COLUMNS + "false AS copied " + from
                        + " ORDER BY p.published_at DESC, p.created_at DESC LIMIT ? OFFSET ?",
                this::toRow,
                pageParameters.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize);
    }

    @Override
    public PageResult<PublicationRow> listStudentVisible(
            UUID studentAccountId,
            UUID schoolId,
            TrainingLevel trainingLevel,
            UUID courseId,
            int page,
            int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        StringBuilder from = new StringBuilder("""
                FROM m6_training.training_template_publications p
                JOIN m6_training.training_templates t
                  ON t.id = p.template_id
                 AND t.status = 'active'
                 AND t.owner_role = 'teacher'
                JOIN m2_course.courses c ON c.id = p.course_id
                JOIN m2_course.course_students cs
                  ON cs.course_id = p.course_id
                 AND cs.student_account_id = ?
                 AND cs.status = 'active'
                WHERE p.status = 'published'
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
            from.append(" AND p.course_id = ?\n");
            parameters.add(courseId);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) " + from, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(studentAccountId);
        pageParameters.add(safeSize);
        pageParameters.add((long) (safePage - 1) * safeSize);
        List<PublicationRow> rows = jdbcTemplate.query(
                SELECT_COLUMNS
                        + """
                          EXISTS (
                              SELECT 1 FROM m6_training.training_templates copy
                              WHERE copy.owner_account_id = ?
                                AND copy.status = 'active'
                                AND copy.source_publication_id = p.id
                          ) AS copied
                          """
                        + from
                        + " ORDER BY p.published_at DESC LIMIT ? OFFSET ?",
                this::toRow,
                pageParameters.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize);
    }

    @Override
    public long countPublishedByTemplate(UUID templateId) {
        return repository.countByTemplateIdAndStatus(
                templateId, TrainingPublicationStatus.PUBLISHED.wireValue());
    }

    @Override
    public TrainingTemplatePublication save(TrainingTemplatePublication publication) {
        TrainingTemplatePublicationEntity entity = repository.findById(publication.id())
                .orElseGet(TrainingTemplatePublicationEntity::new);
        entity.setId(publication.id());
        entity.setTemplateId(publication.templateId());
        entity.setCourseId(publication.courseId());
        entity.setStatus(publication.status().wireValue());
        entity.setPublishedAt(publication.publishedAt());
        entity.setUnpublishedAt(publication.unpublishedAt());
        entity.setUnpublishReason(publication.unpublishReason() == null
                ? null : publication.unpublishReason().wireValue());
        entity.setCreatedBy(publication.createdBy());
        entity.setCreatedAt(publication.createdAt());
        entity.setUpdatedAt(publication.updatedAt());
        repository.saveAndFlush(entity);
        return publication;
    }

    private TrainingTemplatePublication toDomain(TrainingTemplatePublicationEntity entity) {
        return TrainingTemplatePublication.rehydrate(
                entity.getId(),
                entity.getTemplateId(),
                entity.getCourseId(),
                TrainingPublicationStatus.fromWire(entity.getStatus()),
                entity.getPublishedAt(),
                entity.getUnpublishedAt(),
                entity.getUnpublishReason() == null
                        ? null : TrainingUnpublishReason.fromWire(entity.getUnpublishReason()),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private PublicationRow toRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String reason = rs.getString("unpublish_reason");
        return new PublicationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("template_id", UUID.class),
                rs.getString("template_name"),
                TrainingLevel.fromWire(rs.getString("training_level")),
                rs.getString("topic"),
                TrainingTemplateSourceType.fromWire(rs.getString("source_type")),
                rs.getObject("course_id", UUID.class),
                rs.getString("course_name"),
                TrainingPublicationStatus.fromWire(rs.getString("status")),
                instant(rs.getTimestamp("published_at")),
                instant(rs.getTimestamp("unpublished_at")),
                reason == null ? null : TrainingUnpublishReason.fromWire(reason),
                rs.getInt("max_duration_seconds"),
                rs.getInt("virtual_student_count"),
                rs.getBoolean("copied"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
