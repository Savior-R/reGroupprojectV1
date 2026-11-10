package com.shijiangtai.m6training.infrastructure.crossmodule;

import com.shijiangtai.m6training.domain.port.TrainingClassroomPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Direct JDBC seam into {@code m3_classroom.sessions} for training rows.
 *
 * <p>See {@link TrainingClassroomPort}: replace this adapter when M3 owns the training-session
 * creation API. Writes are limited to rows with {@code session_type='training'}.
 */
@Component
public class TrainingClassroomJdbcAdapter implements TrainingClassroomPort {
    private final JdbcTemplate jdbcTemplate;

    public TrainingClassroomJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void create(UUID sessionId, UUID studentAccountId, UUID courseId, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO m3_classroom.sessions
                    (id, session_type, task_id, course_id, student_account_id, mode,
                     status, attempt_no, created_at, updated_at)
                VALUES (?, 'training', NULL, ?, ?, 'free', 'preparing',
                        COALESCE((SELECT MAX(attempt_no) FROM m3_classroom.sessions
                                  WHERE student_account_id = ? AND session_type = 'training'), 0) + 1,
                        ?, ?)
                """,
                sessionId, courseId, studentAccountId, studentAccountId,
                Timestamp.from(now), Timestamp.from(now));
    }

    @Override
    public Optional<ClassroomSession> find(UUID sessionId) {
        List<ClassroomSession> rows = jdbcTemplate.query("""
                SELECT id, student_account_id, course_id, status, started_at, ended_at,
                       duration_ms, created_at
                FROM m3_classroom.sessions
                WHERE id = ? AND session_type = 'training'
                """,
                (rs, rowNum) -> new ClassroomSession(
                        rs.getObject("id", UUID.class),
                        rs.getObject("student_account_id", UUID.class),
                        rs.getObject("course_id", UUID.class),
                        rs.getString("status"),
                        instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("ended_at")),
                        rs.getObject("duration_ms", Long.class),
                        instant(rs.getTimestamp("created_at"))),
                sessionId);
        return rows.stream().findFirst();
    }

    @Override
    public void updateStatus(UUID sessionId, String status, Instant endedAt, Long durationMs) {
        jdbcTemplate.update("""
                UPDATE m3_classroom.sessions
                SET status = ?,
                    ended_at = COALESCE(?, ended_at),
                    duration_ms = COALESCE(?, duration_ms),
                    updated_at = now()
                WHERE id = ? AND session_type = 'training'
                """,
                status,
                endedAt == null ? null : Timestamp.from(endedAt),
                durationMs,
                sessionId);
    }

    @Override
    public boolean allStreamsUploaded(UUID sessionId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM m3_classroom.recordings r
                JOIN m3_classroom.recording_streams s ON s.recording_id = r.id
                WHERE r.session_id = ?
                  AND r.status = 'uploaded'
                  AND s.status = 'uploaded'
                  AND s.stream_type IN ('camera', 'screen', 'audio')
                """, Integer.class, sessionId);
        return count != null && count == 3;
    }

    @Override
    public List<String> recordingObjectKeys(UUID sessionId) {
        return jdbcTemplate.query("""
                SELECT object_key FROM (
                    SELECT s.object_key
                    FROM m3_classroom.recordings r
                    JOIN m3_classroom.recording_streams s ON s.recording_id = r.id
                    WHERE r.session_id = ? AND s.object_key IS NOT NULL
                    UNION ALL
                    SELECT f.object_key
                    FROM m3_classroom.frames f
                    WHERE f.session_id = ? AND f.object_key IS NOT NULL
                ) media
                """,
                (rs, rowNum) -> rs.getString(1),
                sessionId, sessionId);
    }

    @Override
    public void delete(UUID sessionId) {
        jdbcTemplate.update(
                "DELETE FROM m3_classroom.sessions WHERE id = ? AND session_type = 'training'",
                sessionId);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
