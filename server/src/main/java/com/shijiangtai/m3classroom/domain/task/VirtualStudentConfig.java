package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record VirtualStudentConfig(
        UUID id,
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

    public VirtualStudentConfig {
        if (id == null) {
            throw invalid("virtual_students.id");
        }
        name = requireText(name, 1, 20, "virtual_students.name");
        avatar = requireText(avatar, 1, 64, "virtual_students.avatar");
        if (personalityText == null || personalityText.length() > 500) {
            throw invalid("virtual_students.personality_text");
        }
        activity = percentage(activity, "virtual_students.activity");
        questionRate = percentage(questionRate, "virtual_students.question_rate");
        ability = percentage(ability, "virtual_students.ability");
        attentionBaseline = percentage(attentionBaseline, "virtual_students.attention_baseline");
        disciplineProbability = percentage(
                disciplineProbability, "virtual_students.discipline_probability");
        if (seatNo < 1) {
            throw invalid("virtual_students.seat_no");
        }
        if (sortOrder < 1) {
            throw invalid("virtual_students.sort_order");
        }
    }

    private static String requireText(String value, int min, int max, String field) {
        if (value == null) {
            throw invalid(field);
        }
        String trimmed = value.trim();
        if (trimmed.length() < min || trimmed.length() > max) {
            throw invalid(field);
        }
        return trimmed;
    }

    private static BigDecimal percentage(BigDecimal value, String field) {
        if (value == null
                || value.compareTo(BigDecimal.ZERO) < 0
                || value.compareTo(BigDecimal.ONE) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw invalid(field);
        }
        return value;
    }

    private static DomainException invalid(String field) {
        return new DomainException(ErrorCode.TASK_CONFIG_INVALID,
                "虚拟学生配置非法", Map.of("field", field));
    }
}
