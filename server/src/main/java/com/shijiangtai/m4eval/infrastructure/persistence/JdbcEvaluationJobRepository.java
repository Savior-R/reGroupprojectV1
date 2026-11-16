package com.shijiangtai.m4eval.infrastructure.persistence;

import com.shijiangtai.m4eval.application.port.EvaluationJobRepository;
import com.shijiangtai.m4eval.domain.EvaluationJob;
import com.shijiangtai.m4eval.domain.EvaluationJobStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEvaluationJobRepository implements EvaluationJobRepository {

    private static final String COLUMNS = """
            id, evaluation_id, status, attempts, last_error, next_retry_at, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEvaluationJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EvaluationJob save(EvaluationJob job) {
        jdbc.update("""
                INSERT INTO m4_eval.evaluation_jobs (
                    id, evaluation_id, status, attempts, last_error, next_retry_at,
                    created_at, updated_at)
                VALUES (
                    :id, :evaluationId, :status, :attempts, :lastError, :nextRetryAt,
                    :createdAt, :updatedAt)
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status,
                    attempts = EXCLUDED.attempts,
                    last_error = EXCLUDED.last_error,
                    next_retry_at = EXCLUDED.next_retry_at,
                    updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource()
                .addValue("id", job.id())
                .addValue("evaluationId", job.evaluationId())
                .addValue("status", job.status().wireValue())
                .addValue("attempts", job.attempts())
                .addValue("lastError", job.lastError())
                .addValue("nextRetryAt", M4JdbcAccess.timestamp(job.nextRetryAt()))
                .addValue("createdAt", M4JdbcAccess.timestamp(job.createdAt()))
                .addValue("updatedAt", M4JdbcAccess.timestamp(job.updatedAt())));
        return job;
    }

    @Override
    public Optional<EvaluationJob> findById(UUID jobId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM m4_eval.evaluation_jobs WHERE id = :id",
                Map.of("id", jobId),
                this::mapRow).stream().findFirst();
    }

    @Override
    public Optional<EvaluationJob> findByEvaluationId(UUID evaluationId) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM m4_eval.evaluation_jobs WHERE evaluation_id = :evaluationId",
                Map.of("evaluationId", evaluationId),
                this::mapRow).stream().findFirst();
    }

    @Override
    public List<EvaluationJob> findDispatchable(Instant now, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + """
                         FROM m4_eval.evaluation_jobs
                        WHERE status = 'queued'
                          AND next_retry_at IS NOT NULL
                          AND next_retry_at <= :now
                        ORDER BY next_retry_at
                        LIMIT :limit
                        """,
                Map.of("now", M4JdbcAccess.timestamp(now), "limit", limit),
                this::mapRow);
    }

    @Override
    public List<EvaluationJob> findStaleRunning(Instant staleBefore, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + """
                         FROM m4_eval.evaluation_jobs
                        WHERE status = 'running' AND updated_at < :before
                        ORDER BY updated_at
                        LIMIT :limit
                        """,
                Map.of("before", M4JdbcAccess.timestamp(staleBefore), "limit", limit),
                this::mapRow);
    }

    private EvaluationJob mapRow(ResultSet rs, int rowNum) throws SQLException {
        return EvaluationJob.rehydrate(
                M4JdbcAccess.uuid(rs, "id"),
                M4JdbcAccess.uuid(rs, "evaluation_id"),
                EvaluationJobStatus.fromWire(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                M4JdbcAccess.instant(rs, "next_retry_at"),
                M4JdbcAccess.instant(rs, "created_at"),
                M4JdbcAccess.instant(rs, "updated_at"));
    }
}
