package com.shijiangtai.m6training.infrastructure.crossmodule;

import com.shijiangtai.m6training.domain.port.CourseDirectoryPort;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only M2 course adapter (temporary until M2 exposes a module API). */
@Component
public class CourseDirectoryJdbcAdapter implements CourseDirectoryPort {
    private final JdbcTemplate jdbcTemplate;

    public CourseDirectoryJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CourseInfo> findCourse(UUID courseId) {
        List<CourseInfo> rows = jdbcTemplate.query("""
                SELECT id, school_id, name, status, end_at
                FROM m2_course.courses
                WHERE id = ?
                """,
                (rs, rowNum) -> {
                    Timestamp endAt = rs.getTimestamp("end_at");
                    return new CourseInfo(
                            rs.getObject("id", UUID.class),
                            rs.getObject("school_id", UUID.class),
                            rs.getString("name"),
                            "active".equals(rs.getString("status")),
                            endAt == null ? null : endAt.toInstant());
                },
                courseId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean isActiveTeacher(UUID courseId, UUID accountId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_course.course_teachers
                WHERE course_id = ? AND account_id = ? AND status = 'active'
                """, Integer.class, courseId, accountId);
        return count != null && count > 0;
    }

    @Override
    public boolean isActiveStudent(UUID courseId, UUID studentAccountId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_course.course_students
                WHERE course_id = ? AND student_account_id = ? AND status = 'active'
                """, Integer.class, courseId, studentAccountId);
        return count != null && count > 0;
    }
}
