package com.shijiangtai.m4eval.infrastructure.persistence;

import com.shijiangtai.m4eval.application.port.EvaluationIssueRepository;
import com.shijiangtai.m4eval.domain.EvaluationIssue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEvaluationIssueRepository implements EvaluationIssueRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEvaluationIssueRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void replaceAll(UUID evaluationId, List<EvaluationIssue> issues) {
        jdbc.update("DELETE FROM m4_eval.issues WHERE evaluation_id = :evaluationId",
                Map.of("evaluationId", evaluationId));
        if (issues.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = issues.stream()
                .map(issue -> new MapSqlParameterSource()
                        .addValue("id", issue.id())
                        .addValue("evaluationId", evaluationId)
                        .addValue("dimension", issue.dimension())
                        .addValue("startMs", issue.startMs())
                        .addValue("endMs", issue.endMs())
                        .addValue("severity", issue.severity())
                        .addValue("evidence", issue.evidence())
                        .addValue("suggestion", issue.suggestion())
                        .addValue("isSuggestion", issue.isSuggestion())
                        .addValue("suggestionRank", issue.suggestionRank())
                        .addValue("sortOrder", issue.sortOrder()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO m4_eval.issues (
                    id, evaluation_id, dimension, start_ms, end_ms, severity, evidence,
                    suggestion, is_suggestion, suggestion_rank, sort_order)
                VALUES (
                    :id, :evaluationId, :dimension, :startMs, :endMs, :severity, :evidence,
                    :suggestion, :isSuggestion, :suggestionRank, :sortOrder)
                """, batch);
    }

    @Override
    public List<EvaluationIssue> findByEvaluationId(UUID evaluationId) {
        return jdbc.query("""
                SELECT id, evaluation_id, dimension, start_ms, end_ms, severity, evidence,
                       suggestion, is_suggestion, suggestion_rank, sort_order
                FROM m4_eval.issues
                WHERE evaluation_id = :evaluationId
                ORDER BY sort_order, start_ms NULLS LAST, id
                """, Map.of("evaluationId", evaluationId), this::mapRow);
    }

    private EvaluationIssue mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new EvaluationIssue(
                M4JdbcAccess.uuid(rs, "id"),
                M4JdbcAccess.uuid(rs, "evaluation_id"),
                rs.getString("dimension"),
                M4JdbcAccess.nullableLong(rs, "start_ms"),
                M4JdbcAccess.nullableLong(rs, "end_ms"),
                rs.getString("severity"),
                rs.getString("evidence"),
                rs.getString("suggestion"),
                rs.getBoolean("is_suggestion"),
                M4JdbcAccess.nullableInt(rs, "suggestion_rank"),
                rs.getInt("sort_order"));
    }
}
