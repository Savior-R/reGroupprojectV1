package com.shijiangtai.m7teacher.application.view;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record AiScoreView(
        UUID evaluationId,
        String status,
        BigDecimal totalScore,
        Map<String, BigDecimal> dimensionScores) {
}
