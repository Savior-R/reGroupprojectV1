package com.shijiangtai.m4eval.application.command;

import java.util.Objects;
import java.util.UUID;

public record StartEvaluationCommand(UUID evaluationId, UUID jobId, int attempt) {
    public StartEvaluationCommand {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(jobId, "jobId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }
}
