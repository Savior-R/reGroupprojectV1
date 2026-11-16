package com.shijiangtai.m4eval.api.dto;

import java.util.List;
import java.util.UUID;

public record FailCallbackRequest(
        UUID jobId,
        Integer attempt,
        String errorCode,
        String errorMessage,
        List<String> partialReasons) {
}
