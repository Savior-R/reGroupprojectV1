package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.port.TrainingEvaluationPort.DimensionScore;
import com.shijiangtai.m6training.domain.port.TrainingEvaluationPort.Issue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Training report; only the owner may read it (08 §10.6). */
public record TrainingReportView(
        UUID recordId,
        UUID evaluationId,
        String status,
        BigDecimal totalScore,
        List<DimensionScore> dimensions,
        List<Issue> issues,
        List<String> warnings,
        Instant completedAt) {
}
