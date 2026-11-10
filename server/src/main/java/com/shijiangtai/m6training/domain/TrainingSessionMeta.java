package com.shijiangtai.m6training.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** M6-side metadata for a training session stored in {@code m3_classroom.sessions}. */
public record TrainingSessionMeta(
        UUID sessionId,
        UUID templateId,
        TrainingLevel trainingLevel,
        TrainingEffectiveConfig effectiveConfig,
        Instant createdAt) {

    public TrainingSessionMeta {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(trainingLevel, "trainingLevel");
        Objects.requireNonNull(effectiveConfig, "effectiveConfig");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
