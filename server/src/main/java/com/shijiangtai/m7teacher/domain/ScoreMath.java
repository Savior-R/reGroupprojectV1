package com.shijiangtai.m7teacher.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Score arithmetic shared by the calculator, configuration and teacher-score policies. */
public final class ScoreMath {

    public static final BigDecimal ZERO = BigDecimal.ZERO;
    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ONE = BigDecimal.ONE;

    private ScoreMath() {
    }

    /** All persisted and displayed scores use one decimal with HALF_UP (07 §6.1). */
    public static BigDecimal round1(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP);
    }

    /** A score may carry at most one decimal; more precision is rejected rather than silently cut. */
    public static BigDecimal requireScore(BigDecimal value, String field) {
        if (value == null) {
            throw invalid(field, "分数不能为空");
        }
        if (value.compareTo(ZERO) < 0 || value.compareTo(HUNDRED) > 0) {
            throw invalid(field, "分数必须在 0—100 之间");
        }
        if (value.stripTrailingZeros().scale() > 1) {
            throw invalid(field, "分数最多保留一位小数");
        }
        return value.setScale(1, RoundingMode.HALF_UP);
    }

    public static BigDecimal weightedAverage(
            Map<String, BigDecimal> scores, Map<String, Integer> weights) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            BigDecimal score = scores.get(entry.getKey());
            if (score == null) {
                continue;
            }
            total = total.add(score.multiply(BigDecimal.valueOf(entry.getValue()))
                    .divide(HUNDRED, 6, RoundingMode.HALF_UP));
        }
        return round1(total);
    }

    private static M7Exception invalid(String field, String message) {
        return new M7Exception(M7ErrorCode.TEACHER_SCORE_INVALID, message,
                Map.of("field", field));
    }
}
