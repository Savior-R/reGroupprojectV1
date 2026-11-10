package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingLevel;

import java.time.Instant;
import java.util.UUID;

/** Training session returned by create / get / end. */
public record TrainingSessionView(
        UUID sessionId,
        String status,
        UUID templateId,
        UUID courseId,
        TrainingLevel trainingLevel,
        long recommendedDurationMs,
        long maxDurationMs,
        TrainingPublicConfig publicConfig,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt) {
}
