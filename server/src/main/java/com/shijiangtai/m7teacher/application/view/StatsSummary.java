package com.shijiangtai.m7teacher.application.view;

import java.math.BigDecimal;

public record StatsSummary(
        int assigned,
        int submitted,
        int notSubmitted,
        int completed,
        int pendingAi,
        int pendingTeacher,
        int aiPartial,
        int aiFailed,
        int inProgress,
        BigDecimal averageFinal) {
}
