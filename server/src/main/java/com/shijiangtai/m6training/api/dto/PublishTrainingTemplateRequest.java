package com.shijiangtai.m6training.api.dto;

import java.util.UUID;

/** POST /api/v1/training/templates (08 §10.1). */
public record PublishTrainingTemplateRequest(
        UUID sourceTaskId, UUID courseId, String trainingLevel) {
}
