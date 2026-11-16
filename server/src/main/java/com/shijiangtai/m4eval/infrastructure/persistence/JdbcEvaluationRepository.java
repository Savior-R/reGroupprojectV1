package com.shijiangtai.m4eval.infrastructure.persistence;

import com.shijiangtai.m4eval.application.port.EvaluationRepository;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationMode;
import com.shijiangtai.m4eval.domain.EvaluationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEvaluationRepository implements EvaluationRepository {

    private static final String COLUMNS = """
            id, source_type, source_id, submission_id, training_record_id, session_id,
            task_id, course_id, student_account_id, mode, status, total_score, weights,
            task_config_version, training_config_version, model_version, prompt_version,
            rubric_version, attempt_count, warnings, partial_reasons, error_code,
            error_message, token_usage, started_at, created_at, completed_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final M4JsonCodec json;

    public JdbcEvaluationRepository(NamedParameterJdbcTemplate jdbc, M4JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Evaluation save(Evaluation evaluation) {
        jdbc.update("""
                INSERT INTO m4_eval.evaluations (
                    id, source_type, source_id, submission_id, training_record_id, session_id,
                    task_id, course_id, student_account_id, mode, status, total_score, weights,
                    task_config_version, training_config_version, model_version, prompt_version,
                    rubric_version, attempt_count, warnings, partial_reasons, error_code,
                    error_message, token_usage, started_at, created_at, completed_at, updated_at)
                VALUES (
                    :id, :sourceType, :sourceId, :submissionId, :trainingRecordId, :sessionId,
                    :taskId, :courseId, :studentAccountId, :mode, :status, :totalScore,
                    CAST(:weights AS jsonb), :taskConfigVersion, :trainingConfigVersion,
                    :modelVersion, :promptVersion, :rubricVersion, :attemptCount,
                    CAST(:warnings AS jsonb), CAST(:partialReasons AS jsonb), :errorCode,
                    :errorMessage, CAST(:tokenUsage AS jsonb), :startedAt, :createdAt,
                    :completedAt, :updatedAt)
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status,
                    total_score = EXCLUDED.total_score,
                    weights = EXCLUDED.weights,
                    model_version = EXCLUDED.model_version,
                    prompt_version = EXCLUDED.prompt_version,
                    rubric_version = EXCLUDED.rubric_version,
                    attempt_count = EXCLUDED.attempt_count,
                    warnings = EXCLUDED.warnings,
                    partial_reasons = EXCLUDED.partial_reasons,
                    error_code = EXCLUDED.error_code,
                    error_message = EXCLUDED.error_message,
                    token_usage = EXCLUDED.token_usage,
                    started_at = EXCLUDED.started_at,
                    completed_at = EXCLUDED.completed_at,
                    updated_at = EXCLUDED.updated_at
                """, params(evaluation));
        return evaluation;
    }

    @Override
    public Optional<Evaluation> findById(UUID evaluationId) {
        List<Evaluation> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM m4_eval.evaluations WHERE id = :id",
                Map.of("id", evaluationId),
                this::mapRow);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<Evaluation> findBySource(String sourceType, UUID sourceId) {
        List<Evaluation> rows = jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM m4_eval.evaluations"
                        + " WHERE source_type = :sourceType AND source_id = :sourceId",
                Map.of("sourceType", sourceType, "sourceId", sourceId),
                this::mapRow);
        return rows.stream().findFirst();
    }

    @Override
    public List<UUID> findSubmissionIdsWithoutEvaluation(int limit) {
        return jdbc.queryForList("""
                SELECT s.id
                FROM m3_classroom.submissions s
                WHERE s.status = 'submitted'
                  AND NOT EXISTS (
                      SELECT 1 FROM m4_eval.evaluations e
                      WHERE e.source_type = 'task_submission' AND e.source_id = s.id)
                ORDER BY s.submitted_at
                LIMIT :limit
                """, Map.of("limit", limit), UUID.class);
    }

    @Override
    public List<UUID> findTrainingRecordIdsWithoutEvaluation(int limit) {
        return jdbc.queryForList("""
                SELECT r.id
                FROM m6_training.training_records r
                WHERE r.status = 'selected' AND r.deleted_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM m4_eval.evaluations e
                      WHERE e.source_type = 'training_record' AND e.source_id = r.id)
                ORDER BY r.selected_at
                LIMIT :limit
                """, Map.of("limit", limit), UUID.class);
    }

    private MapSqlParameterSource params(Evaluation evaluation) {
        return new MapSqlParameterSource()
                .addValue("id", evaluation.id())
                .addValue("sourceType", evaluation.sourceType())
                .addValue("sourceId", evaluation.sourceId())
                .addValue("submissionId", evaluation.submissionId())
                .addValue("trainingRecordId", evaluation.trainingRecordId())
                .addValue("sessionId", evaluation.sessionId())
                .addValue("taskId", evaluation.taskId())
                .addValue("courseId", evaluation.courseId())
                .addValue("studentAccountId", evaluation.studentAccountId())
                .addValue("mode", evaluation.mode().wireValue())
                .addValue("status", evaluation.status().wireValue())
                .addValue("totalScore", evaluation.totalScore())
                .addValue("weights", json.write(evaluation.weights()))
                .addValue("taskConfigVersion", evaluation.taskConfigVersion())
                .addValue("trainingConfigVersion", evaluation.trainingConfigVersion())
                .addValue("modelVersion", evaluation.modelVersion())
                .addValue("promptVersion", evaluation.promptVersion())
                .addValue("rubricVersion", evaluation.rubricVersion())
                .addValue("attemptCount", evaluation.attemptCount())
                .addValue("warnings", json.write(evaluation.warnings()))
                .addValue("partialReasons", json.write(evaluation.partialReasons()))
                .addValue("errorCode", evaluation.errorCode())
                .addValue("errorMessage", evaluation.errorMessage())
                .addValue("tokenUsage", json.write(evaluation.tokenUsage()))
                .addValue("startedAt", M4JdbcAccess.timestamp(evaluation.startedAt()))
                .addValue("createdAt", M4JdbcAccess.timestamp(evaluation.createdAt()))
                .addValue("completedAt", M4JdbcAccess.timestamp(evaluation.completedAt()))
                .addValue("updatedAt", M4JdbcAccess.timestamp(evaluation.updatedAt()));
    }

    private Evaluation mapRow(ResultSet rs, int rowNum) throws SQLException {
        return Evaluation.rehydrate(
                M4JdbcAccess.uuid(rs, "id"),
                rs.getString("source_type"),
                M4JdbcAccess.uuid(rs, "source_id"),
                M4JdbcAccess.uuid(rs, "submission_id"),
                M4JdbcAccess.uuid(rs, "training_record_id"),
                M4JdbcAccess.uuid(rs, "session_id"),
                M4JdbcAccess.uuid(rs, "task_id"),
                M4JdbcAccess.uuid(rs, "course_id"),
                M4JdbcAccess.uuid(rs, "student_account_id"),
                EvaluationMode.fromWire(rs.getString("mode")),
                EvaluationStatus.fromWire(rs.getString("status")),
                rs.getBigDecimal("total_score"),
                json.readIntMap(rs.getString("weights")),
                M4JdbcAccess.nullableInt(rs, "task_config_version"),
                M4JdbcAccess.nullableInt(rs, "training_config_version"),
                rs.getString("model_version"),
                rs.getString("prompt_version"),
                rs.getString("rubric_version"),
                rs.getInt("attempt_count"),
                json.readStringList(rs.getString("warnings")),
                json.readStringList(rs.getString("partial_reasons")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                json.readMap(rs.getString("token_usage")),
                M4JdbcAccess.instant(rs, "started_at"),
                M4JdbcAccess.instant(rs, "created_at"),
                M4JdbcAccess.instant(rs, "completed_at"),
                M4JdbcAccess.instant(rs, "updated_at"));
    }
}
