package com.shijiangtai.m6training.infrastructure.crossmodule;

import com.shijiangtai.m6training.domain.port.SourceTaskPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only M3 task adapter for template publishing. */
@Component
public class SourceTaskJdbcAdapter implements SourceTaskPort {
    private final JdbcTemplate jdbcTemplate;

    public SourceTaskJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<SourceTask> findTask(UUID taskId) {
        List<SourceTask> rows = jdbcTemplate.query("""
                SELECT t.id, t.course_id, c.school_id, t.mode, t.status,
                       t.config_version, t.config_snapshot::text AS config_snapshot
                FROM m3_classroom.tasks t
                JOIN m2_course.courses c ON c.id = t.course_id
                WHERE t.id = ?
                """,
                (rs, rowNum) -> new SourceTask(
                        rs.getObject("id", UUID.class),
                        rs.getObject("course_id", UUID.class),
                        rs.getObject("school_id", UUID.class),
                        rs.getString("mode"),
                        rs.getString("status"),
                        rs.getInt("config_version"),
                        rs.getString("config_snapshot")),
                taskId);
        return rows.stream().findFirst();
    }
}
