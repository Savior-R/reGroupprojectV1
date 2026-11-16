package com.shijiangtai.m6training.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** Virtual-student defaults stored in the template / effective snapshot (08 §3.2). */
public record TrainingVirtualStudent(
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

    public static final int MAX_STUDENTS = 30;
    public static final int MAX_PERSONALITY_LENGTH = 500;
    public static final int MAX_NAME_LENGTH = 20;
    public static final int MAX_AVATAR_LENGTH = 64;

    public TrainingVirtualStudent {
        name = requireText(name, MAX_NAME_LENGTH, "name");
        avatar = requireText(avatar, MAX_AVATAR_LENGTH, "avatar");
        personalityText = personalityText == null ? "" : personalityText.trim();
        if (personalityText.length() > MAX_PERSONALITY_LENGTH) {
            throw invalid("personality_text", "个性文本不能超过 500 字");
        }
        activity = requireRatio(activity, "activity");
        questionRate = requireRatio(questionRate, "question_rate");
        ability = requireRatio(ability, "ability");
        attentionBaseline = requireRatio(attentionBaseline, "attention_baseline");
        disciplineProbability = requireRatio(disciplineProbability, "discipline_probability");
        if (seatNo < 1) {
            throw invalid("seat_no", "座位号非法");
        }
        if (sortOrder < 1) {
            throw invalid("sort_order", "排序号非法");
        }
    }

    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field, "不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw invalid(field, "长度超限");
        }
        return trimmed;
    }

    private static BigDecimal requireRatio(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0
                || value.compareTo(BigDecimal.ONE) > 0) {
            throw invalid(field, "参数必须在 0—1 之间");
        }
        return value;
    }

    private static TrainingException invalid(String field, String message) {
        return new TrainingException(TrainingError.VALIDATION_ERROR, message,
                Map.of("field", field));
    }

    public static void requireGroup(java.util.List<TrainingVirtualStudent> students) {
        Objects.requireNonNull(students, "students");
        if (students.size() < 1 || students.size() > MAX_STUDENTS) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "虚拟学生数量必须为 1—30", Map.of("field", "virtual_students"));
        }
    }
}
