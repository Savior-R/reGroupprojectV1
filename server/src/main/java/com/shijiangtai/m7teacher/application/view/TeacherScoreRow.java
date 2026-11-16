package com.shijiangtai.m7teacher.application.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TeacherScoreRow(
        UUID teacherScoreId,
        String scoringMode,
        Map<String, BigDecimal> dimensionScores,
        BigDecimal teacherTotal,
        String comment,
        int version,
        UUID gradedBy,
        Instant gradedAt) {
}
