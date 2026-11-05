package com.shijiangtai.m3classroom.api.dto;

import java.util.Objects;
import java.util.UUID;

/** 202 response for an accepted screenshot; serialized snake_case by the global naming strategy. */
public record PerceptionScreenResponse(UUID frameId, String status) {

    public PerceptionScreenResponse {
        Objects.requireNonNull(frameId, "frameId");
        Objects.requireNonNull(status, "status");
    }
}
