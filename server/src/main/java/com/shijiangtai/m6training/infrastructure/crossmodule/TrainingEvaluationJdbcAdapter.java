package com.shijiangtai.m6training.infrastructure.crossmodule;

import com.shijiangtai.m4eval.domain.TrainingRecordSelectedEvent;
import com.shijiangtai.m6training.domain.port.TrainingEvaluationPort;
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
 * <p>Triggering is an after-commit event; status reads and cleanup use {@code m4_eval.*}
 * directly. Report reads are served by M4's public API (06 §9.2) and are not duplicated here.
 */
@Component
public class TrainingEvaluationJdbcAdapter implements TrainingEvaluationPort {
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public TrainingEvaluationJdbcAdapter(
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void requestEvaluation(EvaluationRequest request) {
        // M4 owns the integration event contract (record/session/student only); it reads the
        // effective training config from the training tables when it assembles the input.
        eventPublisher.publishEvent(new TrainingRecordSelectedEvent(
                request.recordId(), request.sessionId(), request.studentAccountId()));
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
}
