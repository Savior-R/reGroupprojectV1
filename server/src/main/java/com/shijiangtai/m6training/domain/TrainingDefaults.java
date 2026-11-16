package com.shijiangtai.m6training.domain;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Default weights / durations / virtual students (08 §5.3). */
public final class TrainingDefaults {
    public static final int SCHEMA_VERSION = 1;
    public static final String CLASSROOM_MODE = "free";
    public static final String DEFAULT_TOPIC = "自由训练";
    public static final int MAX_TOPIC_LENGTH = 100;

    private TrainingDefaults() {
    }

    public static Map<String, Integer> weights() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("content", 30);
        weights.put("expression", 20);
        weights.put("interaction", 20);
        weights.put("board", 15);
        weights.put("time", 15);
        return weights;
    }

    public static TrainingDuration defaultDuration(TrainingLevel level) {
        return level == TrainingLevel.MOCK_EXAM
                ? new TrainingDuration(1200, 1200)
                : new TrainingDuration(900, 1200);
    }

    /** Three 0.5-parameter students used when a free session sends an empty array. */
    public static List<TrainingVirtualStudent> defaultVirtualStudents() {
        return List.of(
                student("林小雨", "avatar-01", 1),
                student("陈嘉豪", "avatar-02", 2),
                student("苏念", "avatar-03", 3));
    }

    private static TrainingVirtualStudent student(String name, String avatar, int seat) {
        BigDecimal half = new BigDecimal("0.50");
        return new TrainingVirtualStudent(
                name, avatar, "", half, half, half, half, half, seat, seat);
    }
}
