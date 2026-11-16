package com.shijiangtai.m7teacher.application.view;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code GET /tasks/{id}/grading-config} response (07 §11.1). */
public record GradingConfigView(
        UUID taskId,
        UUID courseId,
        String mode,
        List<String> dimensions,
        Map<String, Integer> rubricWeights,
        int aiWeight,
        int teacherWeight,
        String scoringMode,
        int configVersion,
        Instant lockedAt,
        boolean canEdit,
        boolean hasScores) {
}
