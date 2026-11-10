package com.shijiangtai.m7teacher.api.dto;

import java.math.BigDecimal;
import java.util.Map;

public record SaveTeacherScoreRequest(
        String scoringMode,
        Map<String, BigDecimal> dimensionScores,
        BigDecimal teacherTotal,
        String comment,
        Integer version) {
}
