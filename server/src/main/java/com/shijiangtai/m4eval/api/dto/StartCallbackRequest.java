package com.shijiangtai.m4eval.api.dto;

import java.util.UUID;

public record StartCallbackRequest(UUID jobId, Integer attempt) {
}
