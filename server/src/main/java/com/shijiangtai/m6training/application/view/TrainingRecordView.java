package com.shijiangtai.m6training.application.view;

import com.shijiangtai.m6training.domain.TrainingLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Training record list / detail row. */
public record TrainingRecordView(
        UUID recordId,
        UUID sessionId,
        UUID templateId,
        String topic,
        TrainingLevel trainingLevel,
        Instant selectedAt,
        Instant expiresAt,
        String status,
        String evaluationStatus,
        BigDecimal totalScore,
        boolean reportAvailable,
        long remainingDays) {
}
