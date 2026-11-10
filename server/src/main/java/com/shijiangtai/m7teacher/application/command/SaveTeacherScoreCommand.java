package com.shijiangtai.m7teacher.application.command;

import com.shijiangtai.m7teacher.domain.ScoringMode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record SaveTeacherScoreCommand(
        UUID submissionId,
        ScoringMode scoringMode,
        Map<String, BigDecimal> dimensionScores,
        BigDecimal teacherTotal,
        String comment,
        int version) {
}
