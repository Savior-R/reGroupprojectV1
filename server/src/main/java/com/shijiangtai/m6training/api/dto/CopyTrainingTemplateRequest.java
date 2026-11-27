package com.shijiangtai.m6training.api.dto;

import java.util.UUID;

/** POST /api/v1/training/my-templates/from-publication (08 §5.3). */
public record CopyTrainingTemplateRequest(UUID publicationId, String name) {
}
