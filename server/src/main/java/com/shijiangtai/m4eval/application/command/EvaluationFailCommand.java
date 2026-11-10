package com.shijiangtai.m4eval.application.command;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EvaluationFailCommand(
        UUID evaluationId,
        UUID jobId,
        int attempt,
        String errorCode,
        String errorMessage,
        List<String> partialReasons) {

    public EvaluationFailCommand {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(jobId, "jobId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        partialReasons = List.copyOf(partialReasons == null ? List.of() : partialReasons);
    }
}
