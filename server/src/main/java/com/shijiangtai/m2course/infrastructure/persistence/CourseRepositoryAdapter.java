package com.shijiangtai.m2course.infrastructure.persistence;

import com.shijiangtai.m2course.application.command.CourseSearchCriteria;
import com.shijiangtai.m2course.application.port.CourseRepository;
import com.shijiangtai.m2course.domain.Course;
import com.shijiangtai.m2course.domain.CourseStatus;
import com.shijiangtai.m2course.domain.CourseTeacher;
import com.shijiangtai.m2course.domain.MajorId;
import com.shijiangtai.m2course.domain.TeacherStatus;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.SchoolId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 课程聚合持久化：courses + course_majors + course_teachers。
 *
 * <p>四个表都是「行 + 状态列」的形态（关联表无代理主键，成员表为软删除状态列），
 * 因此直接用 JdbcTemplate + 显式 SQL 完成聚合读写与 {@code SELECT ... FOR UPDATE} 行锁，
 * 不引入 JPA 实体映射；读模型同样走 JdbcTemplate。
 */
@Repository
public class CourseRepositoryAdapter implements CourseRepository {
    private static final String SELECT_COURSE = """
            SELECT c.id, c.school_id, c.name, c.created_by, c.status, c.end_at, c.archived_at,
                   c.created_at, c.updated_at
              FROM m2_course.courses c
            """;

    private static final RowMapper<CourseRow> COURSE_ROW_MAPPER = (rs, index) -> new CourseRow(
            rs.getObject("id", UUID.class),
            rs.getObject("school_id", UUID.class),
            rs.getString("name"),
            rs.getObject("created_by", UUID.class),
            rs.getString("status"),
            instant(rs, "end_at"),
            instant(rs, "archived_at"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private final NamedParameterJdbcTemplate jdbc;
    private final IdGenerator idGenerator;

    public CourseRepositoryAdapter(NamedParameterJdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    @Override
    public Course save(Course course) {
        int updated = jdbc.update("""
                UPDATE m2_course.courses
                   SET name = :name, status = :status, end_at = :endAt, archived_at = :archivedAt,
                       updated_at = :updatedAt
                 WHERE id = :id
                """, courseParams(course));
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO m2_course.courses (id, school_id, name, created_by, status, end_at,
                                                   archived_at, created_at, updated_at)
                    VALUES (:id, :schoolId, :name, :createdBy, :status, :endAt, :archivedAt,
                            :createdAt, :updatedAt)
                    """, courseParams(course));
        }
        syncMajors(course);
        syncTeachers(course);
        return course;
    }

    @Override
    public Optional<Course> findById(CourseId courseId) {
        return load(List.of(courseId.value()), false).stream().findFirst();
    }

    @Override
    public Optional<Course> findByIdForUpdate(CourseId courseId) {
        return load(List.of(courseId.value()), true).stream().findFirst();
    }

    @Override
    public boolean existsNameInSchool(SchoolId schoolId, String name) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM m2_course.courses WHERE school_id = :schoolId AND name = :name
                """, new MapSqlParameterSource()
                .addValue("schoolId", schoolId.value())
                .addValue("name", name), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public List<Course> search(CourseSearchCriteria criteria) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("schoolId", criteria.schoolId().value())
                .addValue("limit", criteria.size())
                .addValue("offset", criteria.offset());
        String sql = SELECT_COURSE + where(criteria, params)
                + " ORDER BY c.created_at DESC, c.id LIMIT :limit OFFSET :offset";
        List<UUID> ids = jdbc.query(sql, params, (rs, index) -> rs.getObject("id", UUID.class));
        return load(ids, false);
    }

    @Override
    public long count(CourseSearchCriteria criteria) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("schoolId", criteria.schoolId().value());
        String sql = "SELECT count(*) FROM m2_course.courses c " + where(criteria, params);
        Long total = jdbc.queryForObject(sql, params, Long.class);
        return total == null ? 0L : total;
    }

    private String where(CourseSearchCriteria criteria, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder(" WHERE c.school_id = :schoolId");
        if (!criteria.includeArchived()) {
            sql.append(" AND c.status <> 'archived'");
        }
        if (criteria.status() != null) {
            sql.append(" AND c.status = :status");
            params.addValue("status", criteria.status().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (criteria.ended() != null) {
            sql.append(criteria.ended()
                    ? " AND c.end_at IS NOT NULL AND c.end_at <= now()"
                    : " AND (c.end_at IS NULL OR c.end_at > now())");
        }
        if (criteria.keyword() != null) {
            sql.append("""
                     AND (c.name ILIKE :keyword OR EXISTS (
                            SELECT 1 FROM m2_course.course_teachers t
                              JOIN m1_auth.accounts a ON a.id = t.account_id
                             WHERE t.course_id = c.id AND t.status = 'active' AND a.username ILIKE :keyword))
                    """);
            params.addValue("keyword", "%" + criteria.keyword() + "%");
        }
        if (criteria.teacherId() != null) {
            sql.append("""
                     AND EXISTS (SELECT 1 FROM m2_course.course_teachers t
                                  WHERE t.course_id = c.id AND t.status = 'active'
                                    AND t.account_id = :teacherId)
                    """);
            params.addValue("teacherId", criteria.teacherId().value());
        }
        if (criteria.visibleMajorId() != null) {
            sql.append("""
                     AND EXISTS (SELECT 1 FROM m2_course.course_majors m
                                  WHERE m.course_id = c.id AND m.major_id = :majorId)
                    """);
            params.addValue("majorId", criteria.visibleMajorId().value());
        }
        return sql.toString();
    }

    private List<Course> load(List<UUID> ids, boolean forUpdate) {
        if (ids.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        String sql = SELECT_COURSE + " WHERE c.id IN (:ids)" + (forUpdate ? " FOR UPDATE" : "");
        List<CourseRow> rows = jdbc.query(sql, params, COURSE_ROW_MAPPER);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Set<MajorId>> majors = loadMajors(ids);
        Map<UUID, List<CourseTeacher>> teachers = loadTeachers(ids);
        return rows.stream()
                .map(row -> Course.restore(
                        new CourseId(row.id()),
                        new SchoolId(row.schoolId()),
                        new AccountId(row.createdBy()),
                        row.name(),
                        CourseStatus.valueOf(row.status().toUpperCase(java.util.Locale.ROOT)),
                        row.endAt(),
                        row.archivedAt(),
                        row.createdAt(),
                        row.updatedAt(),
                        majors.getOrDefault(row.id(), Set.of()),
                        teachers.getOrDefault(row.id(), List.of())))
                .toList();
    }

    private Map<UUID, Set<MajorId>> loadMajors(Collection<UUID> courseIds) {
        MapSqlParameterSource params = new MapSqlParameterSource("ids", courseIds);
        Map<UUID, Set<MajorId>> result = new java.util.HashMap<>();
        jdbc.query("""
                SELECT course_id, major_id FROM m2_course.course_majors WHERE course_id IN (:ids)
                """, params, rs -> {
            result.computeIfAbsent(rs.getObject("course_id", UUID.class), key -> new LinkedHashSet<>())
                    .add(new MajorId(rs.getObject("major_id", UUID.class)));
        });
        return result;
    }

    private Map<UUID, List<CourseTeacher>> loadTeachers(Collection<UUID> courseIds) {
        MapSqlParameterSource params = new MapSqlParameterSource("ids", courseIds);
        Map<UUID, List<CourseTeacher>> result = new java.util.HashMap<>();
        jdbc.query("""
                SELECT course_id, account_id, status, joined_at, left_at
                  FROM m2_course.course_teachers
                 WHERE course_id IN (:ids)
                 ORDER BY joined_at
                """, params, rs -> {
            UUID courseId = rs.getObject("course_id", UUID.class);
            result.computeIfAbsent(courseId, key -> new ArrayList<>())
                    .add(CourseTeacher.restore(
                            new AccountId(rs.getObject("account_id", UUID.class)),
                            instant(rs, "joined_at"),
                            TeacherStatus.valueOf(rs.getString("status").toUpperCase(java.util.Locale.ROOT)),
                            instant(rs, "left_at")));
        });
        return result;
    }

    private void syncMajors(Course course) {
        Set<UUID> current = course.majorIds().stream().map(MajorId::value).collect(Collectors.toSet());
        MapSqlParameterSource params = new MapSqlParameterSource("courseId", course.id().value());
        if (current.isEmpty()) {
            jdbc.update("DELETE FROM m2_course.course_majors WHERE course_id = :courseId", params);
            return;
        }
        params.addValue("majors", current);
        jdbc.update("""
                DELETE FROM m2_course.course_majors
                 WHERE course_id = :courseId AND major_id NOT IN (:majors)
                """, params);
        for (UUID majorId : current) {
            jdbc.update("""
                    INSERT INTO m2_course.course_majors (course_id, major_id)
                    VALUES (:courseId, :majorId)
                    ON CONFLICT (course_id, major_id) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("courseId", course.id().value())
                    .addValue("majorId", majorId));
        }
    }

    private void syncTeachers(Course course) {
        Instant now = course.updatedAt();
        for (CourseTeacher teacher : course.teachers()) {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("courseId", course.id().value())
                    .addValue("accountId", teacher.accountId().value())
                    .addValue("status", teacher.status().name().toLowerCase(java.util.Locale.ROOT))
                    .addValue("joinedAt", java.sql.Timestamp.from(teacher.joinedAt()))
                    .addValue("leftAt", teacher.leftAt() == null
                            ? null : java.sql.Timestamp.from(teacher.leftAt()))
                    .addValue("updatedAt", java.sql.Timestamp.from(now));
            int updated = jdbc.update("""
                    UPDATE m2_course.course_teachers
                       SET status = :status, joined_at = :joinedAt, left_at = :leftAt, updated_at = :updatedAt
                     WHERE course_id = :courseId AND account_id = :accountId
                    """, params);
            if (updated == 0) {
                params.addValue("id", idGenerator.nextId());
                params.addValue("createdAt", java.sql.Timestamp.from(teacher.joinedAt()));
                jdbc.update("""
                        INSERT INTO m2_course.course_teachers (id, course_id, account_id, status,
                                                               joined_at, left_at, created_at, updated_at)
                        VALUES (:id, :courseId, :accountId, :status, :joinedAt, :leftAt,
                                :createdAt, :updatedAt)
                        """, params);
            }
        }
    }

    private MapSqlParameterSource courseParams(Course course) {
        return new MapSqlParameterSource()
                .addValue("id", course.id().value())
                .addValue("schoolId", course.schoolId().value())
                .addValue("name", course.name())
                .addValue("createdBy", course.createdBy().value())
                .addValue("status", course.status().name().toLowerCase(java.util.Locale.ROOT))
                .addValue("endAt", course.endAt() == null ? null : java.sql.Timestamp.from(course.endAt()))
                .addValue("archivedAt", course.archivedAt() == null
                        ? null : java.sql.Timestamp.from(course.archivedAt()))
                .addValue("createdAt", java.sql.Timestamp.from(course.createdAt()))
                .addValue("updatedAt", java.sql.Timestamp.from(course.updatedAt()));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record CourseRow(
            UUID id,
            UUID schoolId,
            String name,
            UUID createdBy,
            String status,
            Instant endAt,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt) {
    }
}
