package com.shijiangtai.m6training.infrastructure.crossmodule;

import com.shijiangtai.m6training.domain.event.TrainingRecordSelectedEvent;
import com.shijiangtai.m6training.domain.port.TrainingEvaluationPort;
import com.shijiangtai.m6training.infrastructure.persistence.jpa.TrainingJsonCodec;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M4 evaluation seam.
 *
 * <p>Triggering is an after-commit event; reading and cleanup use {@code m4_eval.*} directly
 * because M4 has no module API yet. The event payload is the contract M4 will consume.
 */
@Component
public class TrainingEvaluationJdbcAdapter implements TrainingEvaluationPort {
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final TrainingJsonCodec codec;

    public TrainingEvaluationJdbcAdapter(
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher,
            TrainingJsonCodec codec) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.codec = codec;
    }

    @Override
    public void requestEvaluation(EvaluationRequest request) {
        eventPublisher.publishEvent(new TrainingRecordSelectedEvent(
                request.recordId(),
                request.sessionId(),
                request.studentAccountId(),
                request.courseId(),
                request.trainingLevel(),
                request.config()));
    }

    @Override
    public Optional<EvaluationState> findState(UUID recordId) {
        List<EvaluationState> rows = jdbcTemplate.query("""
                SELECT id, status, total_score, error_code, error_message, completed_at
                FROM m4_eval.evaluations
                WHERE training_record_id = ?
                """,
                (rs, rowNum) -> new EvaluationState(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getBigDecimal("total_score"),
                        rs.getString("error_code"),
                        rs.getString("error_message"),
                        instant(rs.getTimestamp("completed_at"))),
                recordId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<EvaluationReport> findReport(UUID recordId) {
        List<ReportRow> rows = jdbcTemplate.query("""
                SELECT id, status, total_score, warnings::text AS warnings, completed_at
                FROM m4_eval.evaluations
                WHERE training_record_id = ?
                """,
                (rs, rowNum) -> new ReportRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getBigDecimal("total_score"),
                        rs.getString("warnings"),
                        instant(rs.getTimestamp("completed_at"))),
                recordId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ReportRow row = rows.getFirst();
        List<DimensionScore> dimensions = jdbcTemplate.query("""
                SELECT dimension, score, confidence, sub_scores::text AS sub_scores
                FROM m4_eval.dimension_scores
                WHERE evaluation_id = ?
                ORDER BY dimension
                """,
                (rs, rowNum) -> new DimensionScore(
                        rs.getString("dimension"),
                        rs.getBigDecimal("score"),
                        rs.getBigDecimal("confidence"),
                        rs.getString("sub_scores")),
                row.evaluationId());
        List<Issue> issues = jdbcTemplate.query("""
                SELECT dimension, start_ms, end_ms, severity, evidence, suggestion, is_suggestion
                FROM m4_eval.issues
                WHERE evaluation_id = ?
                ORDER BY sort_order
                """,
                (rs, rowNum) -> new Issue(
                        rs.getString("dimension"),
                        rs.getObject("start_ms", Long.class),
                        rs.getObject("end_ms", Long.class),
                        rs.getString("severity"),
                        rs.getString("evidence"),
                        rs.getString("suggestion"),
                        rs.getBoolean("is_suggestion")),
                row.evaluationId());
        return Optional.of(new EvaluationReport(
                row.evaluationId(),
                row.status(),
                row.totalScore(),
                dimensions,
                issues,
                codec.readStringList(row.warnings()),
                row.completedAt()));
    }

    @Override
    public void deleteByRecord(UUID recordId) {
        jdbcTemplate.update("""
                DELETE FROM m4_eval.issues
                WHERE evaluation_id IN (
                    SELECT id FROM m4_eval.evaluations WHERE training_record_id = ?)
                """, recordId);
        jdbcTemplate.update("""
                DELETE FROM m4_eval.dimension_scores
                WHERE evaluation_id IN (
                    SELECT id FROM m4_eval.evaluations WHERE training_record_id = ?)
                """, recordId);
        jdbcTemplate.update("""
                DELETE FROM m4_eval.input_snapshots
                WHERE evaluation_id IN (
                    SELECT id FROM m4_eval.evaluations WHERE training_record_id = ?)
                """, recordId);
        jdbcTemplate.update("""
                DELETE FROM m4_eval.evaluation_jobs
                WHERE evaluation_id IN (
                    SELECT id FROM m4_eval.evaluations WHERE training_record_id = ?)
                """, recordId);
        jdbcTemplate.update(
                "DELETE FROM m4_eval.evaluations WHERE training_record_id = ?", recordId);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record ReportRow(
            UUID evaluationId,
            String status,
            BigDecimal totalScore,
            String warnings,
            Instant completedAt) {
    }
}
