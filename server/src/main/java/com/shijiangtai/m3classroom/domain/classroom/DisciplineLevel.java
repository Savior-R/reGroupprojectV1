package com.shijiangtai.m3classroom.domain.classroom;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

/**
 * Classroom discipline level (05A §5.3): 0 quiet, 1 mild, 2 noisy, 3 chaotic.
 *
 * <p>Levels are ordered; aggregation takes the maximum of the count-based and
 * ratio-based level, then escalates a persisting level 2 to level 3.
 */
public enum DisciplineLevel {
    QUIET(0),
    MILD(1),
    NOISY(2),
    CHAOTIC(3);

    private final int level;

    DisciplineLevel(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public static DisciplineLevel fromLevel(int level) {
        for (DisciplineLevel candidate : values()) {
            if (candidate.level == level) {
                return candidate;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                "未知纪律等级",
                Map.of("field", "discipline_level", "value", level));
    }

    public static DisciplineLevel max(DisciplineLevel left, DisciplineLevel right) {
        return left.level >= right.level ? left : right;
    }
}
