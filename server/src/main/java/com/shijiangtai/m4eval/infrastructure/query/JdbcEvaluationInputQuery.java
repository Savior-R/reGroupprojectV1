package com.shijiangtai.m4eval.infrastructure.query;

import com.shijiangtai.m4eval.application.port.EvaluationInputQuery;
import com.shijiangtai.m4eval.infrastructure.persistence.M4JsonCodec;
import com.shijiangtai.m4eval.infrastructure.persistence.M4JdbcAccess;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only JDBC adapter over the published M2 / M3 / M6 schemas.
 *
 * <p>Rows are limited to the IDs frozen in {@code input_snapshots}, so retries reproduce the same
 * evidence even when M3 keeps writing new rows.
 */
@Component
public class JdbcEvaluationInputQuery implements EvaluationInputQuery {

    private final NamedParameterJdbcTemplate jdbc;
    private final M4JsonCodec json;

    public JdbcEvaluationInputQuery(NamedParameterJdbcTemplate jdbc, M4JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<SubmissionContext> findSubmission(UUID submissionId) {
        return jdbc.query("""
                SELECT s.id, s.task_id, s.session_id, s.student_account_id,
                       t.course_id, t.mode, t.rubric_weights, t.config_version,
                       t.config_snapshot, t.reference_material, t.question_config,
                       t.topic, t.duration_recommended_seconds, t.duration_max_seconds
                FROM m3_classroom.submissions s
                JOIN m3_classroom.tasks t ON t.id = s.task_id
                WHERE s.id = :submissionId
                """, Map.of("submissionId", submissionId), (rs, rowNum) -> new SubmissionContext(
                        M4JdbcAccess.uuid(rs, "id"),
                        M4JdbcAccess.uuid(rs, "task_id"),
                        M4JdbcAccess.uuid(rs, "session_id"),
                        M4JdbcAccess.uuid(rs, "student_account_id"),
                        M4JdbcAccess.uuid(rs, "course_id"),
                        rs.getString("mode"),
                        json.readIntMap(rs.getString("rubric_weights")),
                        rs.getInt("config_version"),
                        json.readMap(rs.getString("config_snapshot")),
                        json.readMap(rs.getString("reference_material")),
                        json.readMap(rs.getString("question_config")),
                        rs.getString("topic"),
                        rs.getInt("duration_recommended_seconds"),
                        rs.getInt("duration_max_seconds")))
                .stream().findFirst();
    }

    @Override
    public Optional<TrainingContext> findTrainingRecord(UUID trainingRecordId) {
        return jdbc.query("""
                SELECT r.id, r.training_session_id, r.student_account_id, r.template_id,
                       r.status, r.expires_at,
                       s.course_id, s.mode, m.effective_config_snapshot
                FROM m6_training.training_records r
                JOIN m3_classroom.sessions s ON s.id = r.training_session_id
                LEFT JOIN m6_training.training_session_meta m
                       ON m.session_id = r.training_session_id
                WHERE r.id = :trainingRecordId
                """, Map.of("trainingRecordId", trainingRecordId), (rs, rowNum) -> {
                    Map<String, Object> config =
                            json.readMap(rs.getString("effective_config_snapshot"));
                    return new TrainingContext(
                            M4JdbcAccess.uuid(rs, "id"),
                            M4JdbcAccess.uuid(rs, "training_session_id"),
                            M4JdbcAccess.uuid(rs, "student_account_id"),
                            M4JdbcAccess.uuid(rs, "course_id"),
                            M4JdbcAccess.uuid(rs, "template_id"),
                            rs.getString("mode"),
                            weightsFromConfig(config),
                            0,
                            config,
                            rs.getString("status"),
                            M4JdbcAccess.instant(rs, "expires_at"));
                }).stream().findFirst();
    }

    @Override
    public Optional<SessionInfo> findSession(UUID sessionId) {
        return jdbc.query("""
                SELECT s.id, s.session_type, s.task_id, s.course_id, s.student_account_id,
                       s.mode, s.status, s.duration_ms, s.stream_offsets, s.started_at,
                       s.ended_at, r.id AS recording_id
                FROM m3_classroom.sessions s
                LEFT JOIN m3_classroom.recordings r ON r.session_id = s.id
                WHERE s.id = :sessionId
                """, Map.of("sessionId", sessionId), (rs, rowNum) -> new SessionInfo(
                        M4JdbcAccess.uuid(rs, "id"),
                        rs.getString("session_type"),
                        M4JdbcAccess.uuid(rs, "task_id"),
                        M4JdbcAccess.uuid(rs, "course_id"),
                        M4JdbcAccess.uuid(rs, "student_account_id"),
                        rs.getString("mode"),
                        rs.getString("status"),
                        M4JdbcAccess.uuid(rs, "recording_id"),
                        M4JdbcAccess.nullableLong(rs, "duration_ms"),
                        json.readMap(rs.getString("stream_offsets")),
                        M4JdbcAccess.instant(rs, "started_at"),
                        M4JdbcAccess.instant(rs, "ended_at")))
                .stream().findFirst();
    }

    @Override
    public List<TextRow> texts(UUID evaluationId, UUID sessionId, UUID afterId, int limit) {
        return jdbc.query("""
                SELECT t.id, t.speaker_type, t.speaker_ref, t.source, t.kind, t.text,
                       t.start_ms, t.end_ms, t.at_ms, t.confidence, t.model_version,
                       t.prompt_version
                FROM m3_classroom.session_texts t
                WHERE t.session_id = :sessionId
                  AND t.status = 'final'
                  AND EXISTS (
                      SELECT 1 FROM m4_eval.input_snapshots s
                      WHERE s.evaluation_id = :evaluationId
                        AND t.id IN (
                            SELECT value::uuid
                            FROM jsonb_array_elements_text(s.text_ids) AS value))
                  AND (CAST(:afterId AS uuid) IS NULL
                       OR (t.at_ms, t.id) > (
                           SELECT t2.at_ms, t2.id
                           FROM m3_classroom.session_texts t2
                           WHERE t2.id = CAST(:afterId AS uuid)))
                ORDER BY t.at_ms, t.id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("evaluationId", evaluationId)
                .addValue("sessionId", sessionId)
                .addValue("afterId", afterId)
                .addValue("limit", limit),
                (rs, rowNum) -> new TextRow(
                        M4JdbcAccess.uuid(rs, "id"),
                        rs.getString("speaker_type"),
                        M4JdbcAccess.uuid(rs, "speaker_ref"),
                        rs.getString("source"),
                        rs.getString("kind"),
                        rs.getString("text"),
                        M4JdbcAccess.nullableLong(rs, "start_ms"),
                        M4JdbcAccess.nullableLong(rs, "end_ms"),
                        rs.getLong("at_ms"),
                        M4JdbcAccess.nullableDouble(rs, "confidence"),
                        rs.getString("model_version"),
                        rs.getString("prompt_version")));
    }

    @Override
    public List<FrameRow> frames(
            UUID evaluationId, UUID sessionId, Long afterTimestampMs, UUID afterId, int limit) {
        return jdbc.query("""
                SELECT f.id, f.timestamp_ms, f.text, f.confidence, f.frame_type, f.object_key,
                       f.content_hash, f.status
                FROM m3_classroom.frames f
                WHERE f.session_id = :sessionId
                  AND EXISTS (
                      SELECT 1 FROM m4_eval.input_snapshots s
                      WHERE s.evaluation_id = :evaluationId
                        AND f.id IN (
                            SELECT value::uuid
                            FROM jsonb_array_elements_text(s.frame_ids) AS value))
                  AND (CAST(:afterTs AS bigint) IS NULL
                       OR f.timestamp_ms > CAST(:afterTs AS bigint)
                       OR (f.timestamp_ms = CAST(:afterTs AS bigint)
                           AND f.id > CAST(:afterId AS uuid)))
                ORDER BY f.timestamp_ms, f.id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("evaluationId", evaluationId)
                .addValue("sessionId", sessionId)
                .addValue("afterTs", afterTimestampMs)
                .addValue("afterId", afterId)
                .addValue("limit", limit),
                (rs, rowNum) -> new FrameRow(
                        M4JdbcAccess.uuid(rs, "id"),
                        rs.getLong("timestamp_ms"),
                        rs.getString("text"),
                        M4JdbcAccess.nullableDouble(rs, "confidence"),
                        rs.getString("frame_type"),
                        rs.getString("object_key"),
                        rs.getString("content_hash"),
                        rs.getString("status")));
    }

    @Override
    public List<EventRow> events(
            UUID evaluationId, UUID sessionId, Long afterSeq, int limit) {
        return jdbc.query("""
                SELECT e.id, e.seq, e.type, e.timestamp_ms, e.actor, e.target, e.payload
                FROM m3_classroom.events e
                WHERE e.session_id = :sessionId
                  AND EXISTS (
                      SELECT 1 FROM m4_eval.input_snapshots s
                      WHERE s.evaluation_id = :evaluationId
                        AND e.id IN (
                            SELECT value::uuid
                            FROM jsonb_array_elements_text(s.event_ids) AS value))
                  AND (CAST(:afterSeq AS bigint) IS NULL
                       OR e.seq > CAST(:afterSeq AS bigint))
                ORDER BY e.seq
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("evaluationId", evaluationId)
                .addValue("sessionId", sessionId)
                .addValue("afterSeq", afterSeq)
                .addValue("limit", limit),
                (rs, rowNum) -> new EventRow(
                        M4JdbcAccess.uuid(rs, "id"),
                        rs.getLong("seq"),
                        rs.getString("type"),
                        rs.getLong("timestamp_ms"),
                        rs.getString("actor"),
                        rs.getString("target"),
                        json.readMap(rs.getString("payload"))));
    }

    @Override
    public List<RecordingStreamRow> recordingStreams(UUID sessionId) {
        return jdbc.query("""
                SELECT rs.id, rs.stream_type, rs.object_key, rs.duration_ms, rs.offset_ms,
                       rs.mime, rs.status
                FROM m3_classroom.recording_streams rs
                JOIN m3_classroom.recordings r ON r.id = rs.recording_id
                WHERE r.session_id = :sessionId AND rs.status <> 'deleted'
                ORDER BY rs.stream_type
                """, Map.of("sessionId", sessionId), (rs, rowNum) -> new RecordingStreamRow(
                        M4JdbcAccess.uuid(rs, "id"),
                        rs.getString("stream_type"),
                        rs.getString("object_key"),
                        M4JdbcAccess.nullableLong(rs, "duration_ms"),
                        rs.getLong("offset_ms"),
                        rs.getString("mime"),
                        rs.getString("status")));
    }

    @Override
    public List<UUID> textIds(UUID sessionId) {
        return jdbc.queryForList("""
                SELECT id FROM m3_classroom.session_texts
                WHERE session_id = :sessionId AND status = 'final'
                ORDER BY at_ms, id
                """, Map.of("sessionId", sessionId), UUID.class);
    }

    @Override
    public List<UUID> frameIds(UUID sessionId) {
        return jdbc.queryForList("""
                SELECT id FROM m3_classroom.frames
                WHERE session_id = :sessionId
                ORDER BY timestamp_ms, id
                """, Map.of("sessionId", sessionId), UUID.class);
    }

    @Override
    public List<UUID> eventIds(UUID sessionId) {
        return jdbc.queryForList("""
                SELECT id FROM m3_classroom.events
                WHERE session_id = :sessionId
                ORDER BY seq
                """, Map.of("sessionId", sessionId), UUID.class);
    }

    @Override
    public List<UUID> recordingStreamIds(UUID sessionId) {
        return jdbc.queryForList("""
                SELECT rs.id FROM m3_classroom.recording_streams rs
                JOIN m3_classroom.recordings r ON r.id = rs.recording_id
                WHERE r.session_id = :sessionId AND rs.status = 'uploaded'
                ORDER BY rs.stream_type
                """, Map.of("sessionId", sessionId), UUID.class);
    }

    @Override
    public long countTexts(UUID sessionId) {
        return count("SELECT count(*) FROM m3_classroom.session_texts"
                + " WHERE session_id = :sessionId AND status = 'final'", sessionId);
    }

    @Override
    public long countFrames(UUID sessionId) {
        return count("SELECT count(*) FROM m3_classroom.frames"
                + " WHERE session_id = :sessionId", sessionId);
    }

    @Override
    public long countEvents(UUID sessionId) {
        return count("SELECT count(*) FROM m3_classroom.events"
                + " WHERE session_id = :sessionId", sessionId);
    }

    @Override
    public boolean isTeacherInGroup(UUID courseId, UUID teacherId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM m2_course.course_teachers
                WHERE course_id = :courseId AND account_id = :teacherId
                  AND status = 'active'
                """, Map.of("courseId", courseId, "teacherId", teacherId), Long.class);
        return count != null && count > 0;
    }

    @Override
    public Optional<UUID> findCourseSchoolId(UUID courseId) {
        return jdbc.query("""
                SELECT school_id FROM m2_course.courses WHERE id = :courseId
                """, Map.of("courseId", courseId),
                (rs, rowNum) -> M4JdbcAccess.uuid(rs, "school_id"))
                .stream().findFirst();
    }

    private long count(String sql, UUID sessionId) {
        Long value = jdbc.queryForObject(sql, Map.of("sessionId", sessionId), Long.class);
        return value == null ? 0 : value;
    }

    private static Map<String, Integer> weightsFromConfig(Map<String, Object> config) {
        if (config == null) {
            return Map.of();
        }
        Object raw = config.get("weights");
        if (raw == null) {
            raw = config.get("rubric_weights");
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Integer> weights = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value instanceof Number number) {
                weights.put(String.valueOf(key), number.intValue());
            }
        });
        return Map.copyOf(weights);
    }
}
