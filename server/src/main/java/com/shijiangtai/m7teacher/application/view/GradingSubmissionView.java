package com.shijiangtai.m7teacher.application.view;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code GET /tasks/{id}/grading-submissions} response (07 §11.2). */
public record GradingSubmissionView(
        UUID taskId,
        UUID courseId,
        String mode,
        String scoringMode,
        Map<String, Integer> rubricWeights,
        SubmissionSummary summary,
        List<GradingSubmissionRow> items,
        long total,
        int page,
        int size) {
}
