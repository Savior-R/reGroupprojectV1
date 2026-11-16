package com.shijiangtai.m7teacher.application.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StudentResultRow(
        UUID submissionId,
        UUID taskId,
        String taskTitle,
        String courseName,
        Instant submittedAt,
        String status,
        BigDecimal finalTotal) {
}
