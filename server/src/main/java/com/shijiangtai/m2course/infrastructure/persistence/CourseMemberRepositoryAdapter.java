package com.shijiangtai.m2course.infrastructure.persistence;

import com.shijiangtai.m2course.application.port.CourseMemberRepository;
import com.shijiangtai.m2course.domain.CourseStudent;
import com.shijiangtai.m2course.domain.JoinMethod;
import com.shijiangtai.m2course.domain.StudentStatus;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 课程学生成员关系持久化：软删除，保留加入 / 退出时间与状态。 */
@Repository
public class CourseMemberRepositoryAdapter implements CourseMemberRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final IdGenerator idGenerator;

    public CourseMemberRepositoryAdapter(NamedParameterJdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    @Override
    public boolean hasActiveStudent(CourseId courseId, AccountId studentId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM m2_course.course_students
                 WHERE course_id = :courseId AND student_account_id = :studentId AND status = 'active'
                """, new MapSqlParameterSource()
                .addValue("courseId", courseId.value())
                .addValue("studentId", studentId.value()), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public void addStudent(CourseId courseId, AccountId studentId, JoinMethod joinMethod, Instant now) {
        jdbc.update("""
                INSERT INTO m2_course.course_students (id, course_id, student_account_id, join_method,
                                                       status, joined_at, created_at, updated_at)
                VALUES (:id, :courseId, :studentId, :joinMethod, 'active', :now, :now, :now)
                """, new MapSqlParameterSource()
                .addValue("id", idGenerator.nextId())
                .addValue("courseId", courseId.value())
                .addValue("studentId", studentId.value())
                .addValue("joinMethod", joinMethod.code())
                .addValue("now", java.sql.Timestamp.from(now)));
    }

    @Override
    public void markStudentStatus(CourseId courseId, AccountId studentId, StudentStatus status, Instant now) {
        jdbc.update("""
                UPDATE m2_course.course_students
                   SET status = :status, left_at = :now, updated_at = :now
                 WHERE course_id = :courseId AND student_account_id = :studentId AND status = 'active'
                """, new MapSqlParameterSource()
                .addValue("courseId", courseId.value())
                .addValue("studentId", studentId.value())
                .addValue("status", status.name().toLowerCase(java.util.Locale.ROOT))
                .addValue("now", java.sql.Timestamp.from(now)));
    }

    @Override
    public List<CourseStudent> findStudents(CourseId courseId, boolean includeHistory) {
        String sql = """
                SELECT student_account_id, join_method, status, joined_at, left_at
                  FROM m2_course.course_students
                 WHERE course_id = :courseId
                """ + (includeHistory ? "" : " AND status = 'active'")
                + " ORDER BY joined_at DESC, student_account_id";
        return jdbc.query(sql, new MapSqlParameterSource("courseId", courseId.value()),
                (rs, index) -> CourseStudent.restore(
                        new AccountId(rs.getObject("student_account_id", UUID.class)),
                        JoinMethod.fromCode(rs.getString("join_method")),
                        instant(rs, "joined_at"),
                        StudentStatus.valueOf(rs.getString("status").toUpperCase(java.util.Locale.ROOT)),
                        instant(rs, "left_at")));
    }

    @Override
    public Map<CourseId, Long> countActiveStudents(Collection<CourseId> courseIds) {
        Map<CourseId, Long> counts = new HashMap<>();
        if (courseIds.isEmpty()) {
            return counts;
        }
        List<UUID> ids = courseIds.stream().map(CourseId::value).toList();
        jdbc.query("""
                SELECT course_id, count(*) AS total
                  FROM m2_course.course_students
                 WHERE course_id IN (:ids) AND status = 'active'
                 GROUP BY course_id
                """, new MapSqlParameterSource("ids", ids), rs -> {
            counts.put(new CourseId(rs.getObject("course_id", UUID.class)), rs.getLong("total"));
        });
        return counts;
    }

    @Override
    public Set<CourseId> findActiveCourseIds(AccountId studentId, Collection<CourseId> courseIds) {
        Set<CourseId> result = new LinkedHashSet<>();
        if (courseIds.isEmpty()) {
            return result;
        }
        List<UUID> ids = courseIds.stream().map(CourseId::value).toList();
        List<UUID> rows = jdbc.queryForList("""
                SELECT course_id FROM m2_course.course_students
                 WHERE student_account_id = :studentId AND status = 'active' AND course_id IN (:ids)
                """, new MapSqlParameterSource()
                .addValue("studentId", studentId.value())
                .addValue("ids", ids), UUID.class);
        rows.forEach(courseId -> result.add(new CourseId(courseId)));
        return result;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
