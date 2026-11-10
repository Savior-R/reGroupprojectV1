package com.shijiangtai.m7teacher.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Output of {@link FinalScoreCalculator}; only completed results carry totals. */
public record FinalScoreResult(
        FinalScoreStatus status,
        BigDecimal aiTotal,
        BigDecimal teacherTotal,
        BigDecimal finalTotal,
        Map<String, BigDecimal> finalDimensions,
        String errorCode,
        String errorMessage) {

    public FinalScoreResult {
        finalDimensions = finalDimensions == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(finalDimensions));
    }

    public static FinalScoreResult pending(FinalScoreStatus status, BigDecimal aiTotal,
            BigDecimal teacherTotal, String errorCode, String errorMessage) {
        return new FinalScoreResult(status, aiTotal, teacherTotal, null, Map.of(),
                errorCode, errorMessage);
    }
}
