package com.shijiangtai.m4eval.application.command;

import com.shijiangtai.m4eval.domain.DimensionScore;
import com.shijiangtai.m4eval.domain.EvaluationIssue;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Worker result callback (06 §5.3). Never carries a total — Java composes it. */
public record EvaluationResultCommand(
        UUID evaluationId,
        UUID jobId,
        int attempt,
        String requestedStatus,
        String modelVersion,
        String promptVersion,
        String rubricVersion,
        List<DimensionScore> dimensions,
        List<EvaluationIssue> issues,
        List<String> warnings,
        List<String> partialReasons,
        Map<String, Object> inputFlags,
        Map<String, Object> tokenUsage) {

    public EvaluationResultCommand {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(jobId, "jobId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        dimensions = List.copyOf(dimensions == null ? List.of() : dimensions);
        issues = List.copyOf(issues == null ? List.of() : issues);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        partialReasons = List.copyOf(partialReasons == null ? List.of() : partialReasons);
        inputFlags = inputFlags == null ? Map.of() : Map.copyOf(inputFlags);
        tokenUsage = tokenUsage == null ? Map.of() : Map.copyOf(tokenUsage);
    }
}
