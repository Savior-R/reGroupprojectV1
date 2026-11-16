package com.shijiangtai.m6training.application.command;

import java.math.BigDecimal;

/**
 * Raw virtual-student input. Kept separate from the domain record so bean validation failures
 * surface as M6 {@code VALIDATION_ERROR} instead of Jackson binding errors.
 */
public record VirtualStudentCommand(
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
