package com.shijiangtai.m4eval.infrastructure.persistence;

import com.shijiangtai.m4eval.application.port.DimensionScoreRepository;
import com.shijiangtai.m4eval.domain.DimensionCatalog;
import com.shijiangtai.m4eval.domain.DimensionScore;
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
public class JdbcDimensionScoreRepository implements DimensionScoreRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final M4JsonCodec json;

    public JdbcDimensionScoreRepository(NamedParameterJdbcTemplate jdbc, M4JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void replaceAll(UUID evaluationId, List<DimensionScore> scores) {
        jdbc.update("DELETE FROM m4_eval.dimension_scores WHERE evaluation_id = :evaluationId",
                Map.of("evaluationId", evaluationId));
        if (scores.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = scores.stream()
                .map(score -> new MapSqlParameterSource()
                        .addValue("id", score.id())
                        .addValue("evaluationId", evaluationId)
                        .addValue("dimension", score.dimension())
                        .addValue("score", score.score())
                        .addValue("subScores", json.write(score.subScores()))
                        .addValue("subConfidences", json.write(score.subConfidences()))
                        .addValue("confidence", score.confidence()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO m4_eval.dimension_scores (
                    id, evaluation_id, dimension, score, sub_scores, sub_confidences, confidence)
                VALUES (
                    :id, :evaluationId, :dimension, :score, CAST(:subScores AS jsonb),
                    CAST(:subConfidences AS jsonb), :confidence)
                """, batch);
    }

    @Override
    public List<DimensionScore> findByEvaluationId(UUID evaluationId) {
        return jdbc.query("""
                SELECT id, evaluation_id, dimension, score, sub_scores, sub_confidences, confidence
                FROM m4_eval.dimension_scores
                WHERE evaluation_id = :evaluationId
                ORDER BY CASE dimension
                    WHEN 'content' THEN 1
                    WHEN 'expression' THEN 2
                    WHEN 'interaction' THEN 3
                    WHEN 'board' THEN 4
                    WHEN 'time' THEN 5
                    ELSE 6
                END
                """, Map.of("evaluationId", evaluationId), this::mapRow);
    }

    private DimensionScore mapRow(ResultSet rs, int rowNum) throws SQLException {
        String dimension = rs.getString("dimension");
        if (!DimensionCatalog.isKnownDimension(dimension)) {
            throw new SQLException("unknown dimension in m4_eval.dimension_scores: " + dimension);
        }
        return new DimensionScore(
                M4JdbcAccess.uuid(rs, "id"),
                M4JdbcAccess.uuid(rs, "evaluation_id"),
                dimension,
                rs.getBigDecimal("score"),
                json.readDecimalMap(rs.getString("sub_scores")),
                json.readDecimalMap(rs.getString("sub_confidences")),
                rs.getBigDecimal("confidence"));
    }
}
