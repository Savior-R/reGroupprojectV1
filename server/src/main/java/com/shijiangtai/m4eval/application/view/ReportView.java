package com.shijiangtai.m4eval.application.view;

import com.shijiangtai.m4eval.domain.DimensionScore;
import com.shijiangtai.m4eval.domain.DimensionCatalog;
import com.shijiangtai.m4eval.domain.Evaluation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Report read model (06 §9.2). */
public record ReportView(
        UUID evaluationId,
        UUID submissionId,
        UUID trainingRecordId,
        UUID sessionId,
        String mode,
        String status,
        BigDecimal totalScore,
        Map<String, Integer> weights,
        RadarView radar,
        List<DimensionView> dimensions,
        List<String> missingDimensions,
        List<IssueView> suggestions,
        List<String> warnings,
        List<String> partialReasons,
        MetaView meta) {

    public record RadarView(List<String> axes, List<BigDecimal> values) {
    }

    public record DimensionView(
            String dimension,
            BigDecimal score,
            Map<String, BigDecimal> subScores,
            Map<String, BigDecimal> subConfidences,
            BigDecimal confidence) {
    }

    public record MetaView(
            String modelVersion,
            String promptVersion,
            String rubricVersion,
            Integer configVersion,
            int attemptCount,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt) {
    }

    public static ReportView build(
            Evaluation evaluation,
            List<DimensionScore> scores,
            List<IssueView> suggestions,
            Integer configVersion) {
        Map<String, DimensionScore> byDimension = new LinkedHashMap<>();
        for (DimensionScore score : scores) {
            byDimension.put(score.dimension(), score);
        }
        List<String> axes = DimensionCatalog.applicableDimensions(evaluation.mode());
        List<DimensionView> dimensions = new ArrayList<>();
        List<BigDecimal> radarValues = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String axis : axes) {
            DimensionScore score = byDimension.get(axis);
            if (score == null) {
                missing.add(axis);
                radarValues.add(null);
                continue;
            }
            dimensions.add(new DimensionView(
                    score.dimension(),
                    score.score(),
                    score.subScores(),
                    score.subConfidences(),
                    score.confidence()));
            radarValues.add(score.score());
        }
        return new ReportView(
                evaluation.id(),
                evaluation.submissionId(),
                evaluation.trainingRecordId(),
                evaluation.sessionId(),
                evaluation.mode().wireValue(),
                evaluation.status().wireValue(),
                evaluation.totalScore(),
                evaluation.weights(),
                new RadarView(axes, radarValues),
                dimensions,
                missing,
                suggestions,
                evaluation.warnings(),
                evaluation.partialReasons(),
                new MetaView(
                        evaluation.modelVersion(),
                        evaluation.promptVersion(),
                        evaluation.rubricVersion(),
                        configVersion,
                        evaluation.attemptCount(),
                        evaluation.createdAt(),
                        evaluation.startedAt(),
                        evaluation.completedAt()));
    }
}
