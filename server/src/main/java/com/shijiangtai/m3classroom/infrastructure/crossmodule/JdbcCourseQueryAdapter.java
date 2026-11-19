package com.shijiangtai.m3classroom.infrastructure.crossmodule;

import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.CourseStudentInfo;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M2 course directory for the docker/demo runtime.
 *
 * <p>M3's task and classroom modules previously used an in-memory adapter that only knew the
 * fixed dev course id. The demo seed (and every real course) lives in {@code m2_course}, so this
 * adapter reads the real course, teacher group and active student roster.
 */
@Component
@Profile("docker")
public class JdbcCourseQueryAdapter implements CourseQueryPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCourseQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CourseInfo> findCourse(CourseId courseId) {
        List<CourseRow> rows = jdbcTemplate.query("""
                SELECT id, school_id, name, status, end_at
                FROM m2_course.courses
                WHERE id = ?
                """,
                (rs, rowNum) -> new CourseRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("school_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getTimestamp("end_at") == null
                                ? null : rs.getTimestamp("end_at").toInstant()),
                courseId.value());
        return rows.stream().findFirst().map(row -> new CourseInfo(
                row.id(),
                row.schoolId(),
                row.name(),
                "active".equals(row.status()),
                row.endAt(),
                teacherIds(row.id())));
    }

    @Override
    public boolean isTeacherInGroup(CourseId courseId, AccountId teacherId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_course.course_teachers
                WHERE course_id = ? AND account_id = ? AND status = 'active'
                """, Integer.class, courseId.value(), teacherId.value());
        return count != null && count > 0;
    }

    @Override
    public List<CourseStudentInfo> activeStudents(CourseId courseId) {
        return jdbcTemplate.query("""
                SELECT cs.student_account_id, sp.class_id
                FROM m2_course.course_students cs
                LEFT JOIN m1_auth.student_profiles sp
                  ON sp.account_id = cs.student_account_id
                WHERE cs.course_id = ? AND cs.status = 'active'
                ORDER BY cs.joined_at, cs.student_account_id
                """,
                (rs, rowNum) -> new CourseStudentInfo(
                        rs.getObject("student_account_id", UUID.class),
                        rs.getObject("class_id", UUID.class)),
                courseId.value());
    }

    private List<UUID> teacherIds(UUID courseId) {
        return jdbcTemplate.query("""
                SELECT account_id FROM m2_course.course_teachers
                WHERE course_id = ? AND status = 'active'
                ORDER BY joined_at, account_id
                """,
                (rs, rowNum) -> rs.getObject("account_id", UUID.class),
                courseId);
    }

    private record CourseRow(
            UUID id,
            UUID schoolId,
            String name,
            String status,
            Instant endAt) {
    }
}
