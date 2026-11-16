package com.shijiangtai.m7teacher.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Inputs to the final-score calculation. {@code dataErrorCode} is set when M3/M4 data is
 * missing or inconsistent; when {@code ai_weight > 0} it produces {@code ai_failed} +
 * {@code DATA_INCONSISTENT} instead of a fabricated score (07 §6.6).
 */
public record FinalScoreInput(
        int aiWeight,
        int teacherWeight,
        ScoringMode scoringMode,
        Rubric rubric,
        AiEvaluationSnapshot ai,
        TeacherScore teacher,
        String dataErrorCode,
        String dataErrorMessage) {

    public FinalScoreInput {
        if (rubric == null) {
            throw new IllegalArgumentException("rubric");
        }
        if (scoringMode == null) {
            throw new IllegalArgumentException("scoringMode");
        }
    }

    public static FinalScoreInput inconsistent(
            int aiWeight, int teacherWeight, ScoringMode scoringMode, Rubric rubric,
            AiEvaluationSnapshot ai, TeacherScore teacher, String message) {
        return new FinalScoreInput(aiWeight, teacherWeight, scoringMode, rubric, ai, teacher,
                "DATA_INCONSISTENT", message);
    }

    public boolean hasTeacherDimensions() {
        return teacher != null
                && teacher.scoringMode() == ScoringMode.DIMENSION
                && !teacher.dimensionScores().isEmpty();
    }

    public Map<String, BigDecimal> teacherDimensions() {
        return teacher == null ? Map.of() : teacher.dimensionScores();
    }
}
