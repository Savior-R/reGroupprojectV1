package com.shijiangtai.m7teacher.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record BatchSaveRequest(List<BatchItem> items) {

    public record BatchItem(
            UUID submissionId,
            String scoringMode,
            Map<String, BigDecimal> dimensionScores,
            BigDecimal teacherTotal,
            String comment,
            Integer version) {
    }
}
