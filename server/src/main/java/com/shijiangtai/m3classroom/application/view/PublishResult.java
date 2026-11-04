package com.shijiangtai.m3classroom.application.view;

import java.time.Instant;
import java.util.UUID;

public record PublishResult(
        UUID taskId,
        String status,
        int configVersion,
        long assignedCount,
        Instant publishedAt) {
}
