package com.shijiangtai.m7teacher.application.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** {@code GET /submissions/{id}/result} (07 §11.6). */
public record StudentResultDetailView(
        UUID submissionId,
        UUID taskId,
        String taskTitle,
        String courseName,
        Instant submittedAt,
        String status,
        BigDecimal aiTotal,
        BigDecimal teacherTotal,
        BigDecimal finalTotal,
        Map<String, BigDecimal> finalDimensions,
        String teacherComment,
        String aiReportUrl,
        String replayUrl,
        String errorCode,
        String errorMessage) {
}
