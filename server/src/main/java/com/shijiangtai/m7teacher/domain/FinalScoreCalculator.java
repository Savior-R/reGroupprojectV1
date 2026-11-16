package com.shijiangtai.m7teacher.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Final total / dimension composition and status precedence (07 §6.1—§6.3).
 *
 * <p>Only the items whose weight is greater than zero take part in the status decision. Zero
 * weight items never require a score, and AI status is ignored entirely when
 * {@code ai_weight = 0}.
 */
public class FinalScoreCalculator {

    public FinalScoreResult calculate(FinalScoreInput input) {
        BigDecimal teacherTotal = input.teacher() == null
                ? null : input.teacher().teacherTotal();
        if (input.aiWeight() < 0 || input.teacherWeight() < 0
                || input.aiWeight() > 100 || input.teacherWeight() > 100
                || input.aiWeight() + input.teacherWeight() != 100) {
            return failed("DATA_INCONSISTENT", "评分配置权重非法", teacherTotal);
        }

        AiEvaluationSnapshot ai = input.ai();
        BigDecimal aiTotal = ai != null && ai.completed() ? ai.totalScore() : null;

        if (input.dataErrorCode() != null && input.aiWeight() > 0) {
            return failed(input.dataErrorCode(), input.dataErrorMessage(), teacherTotal);
        }

        if (input.aiWeight() > 0) {
            if (ai == null) {
                return FinalScoreResult.pending(FinalScoreStatus.PENDING_AI, null,
                        teacherTotal, "DATA_INCONSISTENT", "AI 评测未创建");
            }
            if (ai.partial()) {
                return FinalScoreResult.pending(FinalScoreStatus.AI_PARTIAL, null,
                        teacherTotal, ai.errorCode(), ai.errorMessage());
            }
            if (ai.failed()) {
                return FinalScoreResult.pending(FinalScoreStatus.AI_FAILED, null,
                        teacherTotal, ai.errorCode() == null ? "EVALUATION_FAILED" : ai.errorCode(),
                        ai.errorMessage());
            }
            if (ai.pending()) {
                return FinalScoreResult.pending(FinalScoreStatus.PENDING_AI, null,
                        teacherTotal, null, null);
            }
            if (aiTotal == null) {
                return failed("DATA_INCONSISTENT", "AI 评测已完成但缺少总分", teacherTotal);
            }
        }

        if (input.teacherWeight() > 0 && teacherTotal == null) {
            return FinalScoreResult.pending(FinalScoreStatus.PENDING_TEACHER, aiTotal,
                    null, null, null);
        }
        if (teacherTotal == null && input.teacherWeight() == 0) {
            teacherTotal = null;
        }

        BigDecimal finalTotal = composeTotal(input.aiWeight(), aiTotal,
                input.teacherWeight(), teacherTotal);
        Map<String, BigDecimal> dimensions = composeDimensions(input, aiTotal, ai);
        return new FinalScoreResult(FinalScoreStatus.COMPLETED, aiTotal, teacherTotal,
                finalTotal, dimensions, null, null);
    }

    private BigDecimal composeTotal(
            int aiWeight, BigDecimal aiTotal, int teacherWeight, BigDecimal teacherTotal) {
        if (teacherWeight == 0) {
            return ScoreMath.round1(aiTotal);
        }
        if (aiWeight == 0) {
            return ScoreMath.round1(teacherTotal);
        }
        BigDecimal aiPart = aiTotal.multiply(BigDecimal.valueOf(aiWeight))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal teacherPart = teacherTotal.multiply(BigDecimal.valueOf(teacherWeight))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        return ScoreMath.round1(aiPart.add(teacherPart));
    }

    private Map<String, BigDecimal> composeDimensions(
            FinalScoreInput input, BigDecimal aiTotal, AiEvaluationSnapshot ai) {
        LinkedHashMap<String, BigDecimal> result = new LinkedHashMap<>();
        TeacherScore teacher = input.teacher();
        boolean teacherDimensionMode = teacher != null
                && teacher.scoringMode() == ScoringMode.DIMENSION;

        for (String dimension : input.rubric().dimensions()) {
            BigDecimal aiScore = ai != null && ai.completed()
                    ? ai.dimensionScores().get(dimension) : null;
            BigDecimal teacherScore = teacherDimensionMode
                    ? teacher.dimensionScores().get(dimension) : null;
            BigDecimal value;
            if (teacher != null && teacher.scoringMode() == ScoringMode.TOTAL) {
                // Total mode: final dimensions come from AI when AI counts.
                value = input.aiWeight() > 0 ? aiScore : null;
            } else if (input.aiWeight() == 0) {
                value = teacherScore;
            } else if (input.teacherWeight() == 0) {
                value = aiScore;
            } else if (aiScore != null && teacherScore != null) {
                value = aiScore.multiply(BigDecimal.valueOf(input.aiWeight()))
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                        .add(teacherScore.multiply(BigDecimal.valueOf(input.teacherWeight()))
                                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            } else {
                // A zero-rubric-weight dimension may be omitted by the teacher; fall back to
                // whichever side has a value instead of dropping the dimension.
                value = aiScore != null ? aiScore : teacherScore;
            }
            if (value != null) {
                result.put(dimension, ScoreMath.round1(value));
            }
        }
        return result;
    }

    private static FinalScoreResult failed(
            String errorCode, String errorMessage, BigDecimal teacherTotal) {
        return FinalScoreResult.pending(FinalScoreStatus.AI_FAILED, null, teacherTotal,
                errorCode, errorMessage);
    }
}
