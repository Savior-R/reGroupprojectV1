package com.shijiangtai.m6training.application.view;

import java.time.Instant;
import java.util.UUID;

/** Material upload response (08 §10.4). */
public record TrainingMaterialView(
        UUID materialId,
        String filename,
        String mime,
        long size,
        int charCount,
        String preview,
        String status,
        String usage,
        UUID templateId,
        Instant expiresAt) {
}
