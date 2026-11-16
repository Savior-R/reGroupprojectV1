package com.shijiangtai.m6training.api.dto;

import java.util.UUID;

/** POST /api/v1/training/records (08 §10.5). */
public record SelectTrainingRecordRequest(UUID sessionId) {
}
