package com.shijiangtai.m7teacher.application.view;

import java.util.List;
import java.util.UUID;

/** {@code GET /tasks/{id}/stats} response (07 §11.4). */
public record TaskStatsView(
        UUID taskId,
        UUID courseId,
        UUID classId,
        StatsSummary summary,
        List<DimensionStat> dimensions,
        List<String> weakDimensions,
        boolean sampleSufficient) {
}
