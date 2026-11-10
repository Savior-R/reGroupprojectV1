package com.shijiangtai.m7teacher.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * M4 terminal evaluation event (contract 06 / 07 §6.4).
 *
 * <p>M4 publishes the event; M7 consumes completed / partial / failed for task submissions
 * only. Training evaluations are ignored. The scheduled reconciler covers lost events.
 */
public record EvaluationTerminalEvent(
        UUID evaluationId,
        String sourceType,
        UUID sourceId,
        String status,
        Instant completedAt,
        int attempt) {

    public boolean taskSubmission() {
        return "task_submission".equals(sourceType);
    }
}
