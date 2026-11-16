package com.shijiangtai.m6training.api.dto;

import java.math.BigDecimal;

/** Raw virtual-student input; validated by the domain after deserialization. */
public record VirtualStudentRequest(
        String name,
        String avatar,
        String personalityText,
        BigDecimal activity,
        BigDecimal questionRate,
        BigDecimal ability,
        BigDecimal attentionBaseline,
        BigDecimal disciplineProbability,
        Integer seatNo,
        Integer sortOrder) {
}
