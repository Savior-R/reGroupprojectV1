package com.shijiangtai.m3classroom.domain.task;

import java.time.Instant;
import java.util.UUID;

public record TaskPublishedEvent(
        UUID taskId,
        UUID courseId,
        UUID schoolId,
        Instant publishedAt,
        int assignedCount) {
}
