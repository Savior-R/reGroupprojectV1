package com.shijiangtai.m4eval.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ResultCallbackRequest(
        UUID jobId,
        Integer attempt,
        String status,
        String modelVersion,
        String promptVersion,
        String rubricVersion,
        List<DimensionPayload> dimensions,
        List<IssuePayload> issues,
        List<String> warnings,
        List<String> partialReasons,
        Map<String, Object> inputFlags,
        Map<String, Object> tokenUsage) {

    public record DimensionPayload(
            String dimension,
            BigDecimal score,
            Map<String, BigDecimal> subScores,
            Map<String, BigDecimal> subConfidences,
            BigDecimal confidence) {
    }

    public record IssuePayload(
            String dimension,
            Long startMs,
            Long endMs,
            String severity,
            String evidence,
            String suggestion,
            Boolean isSuggestion,
            Integer suggestionRank) {
    }
}
