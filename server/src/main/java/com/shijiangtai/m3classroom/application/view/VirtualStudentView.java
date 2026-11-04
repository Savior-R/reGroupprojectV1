package com.shijiangtai.m3classroom.application.view;

import java.math.BigDecimal;
import java.util.UUID;

public record VirtualStudentView(
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
