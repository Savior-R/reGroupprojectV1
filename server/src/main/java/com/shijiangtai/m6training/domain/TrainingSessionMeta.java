package com.shijiangtai.m6training.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** M6-side metadata for a training session stored in {@code m3_classroom.sessions} (08 §3.4). */
public record TrainingSessionMeta(
        UUID sessionId,
        UUID templateId,
        UUID publicationId,
        TrainingLevel trainingLevel,
        TrainingEffectiveConfig effectiveConfig,
        Instant createdAt) {

    public TrainingSessionMeta {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(trainingLevel, "trainingLevel");
        Objects.requireNonNull(effectiveConfig, "effectiveConfig");
        Objects.requireNonNull(createdAt, "createdAt");
        if (publicationId != null && templateId == null) {
            throw new TrainingException(TrainingError.INTERNAL_ERROR,
                    "发布来源缺少模板", java.util.Map.of("field", "publication_id"));
        }
    }
}
