package com.shijiangtai.m7teacher.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Derived final score for one submission (07 §3.3). */
public record FinalScore(
        UUID submissionId,
        UUID taskId,
        UUID courseId,
        UUID studentAccountId,
        UUID aiEvaluationId,
        UUID teacherScoreId,
        BigDecimal aiTotal,
        BigDecimal teacherTotal,
        BigDecimal finalTotal,
        Map<String, BigDecimal> finalDimensions,
        FinalScoreStatus status,
        UUID classIdSnapshot,
        int configVersion,
        String errorCode,
        String errorMessage,
        Instant computedAt) {

    public FinalScore {
        Objects.requireNonNull(submissionId, "submissionId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(studentAccountId, "studentAccountId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(computedAt, "computedAt");
        finalDimensions = finalDimensions == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(finalDimensions));
        if (status == FinalScoreStatus.COMPLETED && finalTotal == null) {
            throw new IllegalArgumentException("completed final score requires final_total");
        }
    }

    public boolean completed() {
        return status == FinalScoreStatus.COMPLETED;
    }
}
