package com.shijiangtai.m3classroom.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record VirtualStudentRequest(
        UUID virtualStudentId,
        String name,
        String avatar,
        String personalityText,
        BigDecimal activity,
        BigDecimal questionRate,
        BigDecimal ability,
        BigDecimal attentionBaseline,
        BigDecimal disciplineProbability,
        int seatNo,
        int sortOrder) {
}
