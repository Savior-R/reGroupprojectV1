package com.shijiangtai.m7teacher.api.dto;

public record UpdateGradingConfigRequest(
        Integer aiWeight,
        Integer teacherWeight,
        String scoringMode,
        Integer configVersion) {
}
