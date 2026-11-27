package com.shijiangtai.m6training.api.dto;

import java.util.UUID;

/** POST /api/v1/training/my-templates/{template_id}/publications (08 §10.2). */
public record PublishTrainingTemplateRequest(UUID courseId) {
}
