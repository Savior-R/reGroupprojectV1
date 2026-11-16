package com.shijiangtai.m6training.application.view;

import java.time.Instant;
import java.util.UUID;

/** Free-training material upload response. */
public record TrainingMaterialView(
        UUID materialId,
        String filename,
        int charCount,
        String preview,
        Instant expiresAt) {
}
