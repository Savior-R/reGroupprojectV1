package com.shijiangtai.m3classroom.application.command;

import java.math.BigDecimal;
import java.util.UUID;

public record VirtualStudentSpec(
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
