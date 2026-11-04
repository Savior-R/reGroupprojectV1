package com.shijiangtai.m3classroom.application.view;

import java.time.Instant;
import java.util.UUID;

public record TaskCardView(
        UUID taskId,
        UUID courseId,
        String mode,
        String topic,
        int durationSeconds,
        int questionCount,
        int virtualStudentCount,
        String status,
        long assignedCount,
        long submittedCount,
        Instant publishedAt,
        Instant updatedAt) {
}
