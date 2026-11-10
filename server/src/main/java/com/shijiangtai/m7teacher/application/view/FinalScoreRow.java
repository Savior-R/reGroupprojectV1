package com.shijiangtai.m7teacher.application.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record FinalScoreRow(
        String status,
        BigDecimal aiTotal,
        BigDecimal teacherTotal,
        BigDecimal finalTotal,
        Map<String, BigDecimal> finalDimensions,
        String errorCode,
        Instant computedAt) {
}
