package com.shijiangtai.m4eval.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published after a result / fail / dead-letter transaction commits (06 §5.8).
 *
 * <p>M7 is the only consumer that needs it for final scores; M6 consumes training terminals. The
 * event is emitted for {@code completed}, {@code partial} and {@code failed} so M7 never stays
 * pending forever.
 */
public record EvaluationTerminalEvent(
        UUID evaluationId,
        String sourceType,
        UUID sourceId,
        UUID sessionId,
        EvaluationStatus status,
        Instant completedAt,
        int attempt) {

    public EvaluationTerminalEvent {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(completedAt, "completedAt");
        if (!status.terminal()) {
            throw new IllegalArgumentException("terminal event requires a terminal status");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }
}
