package com.shijiangtai.m3classroom.domain.classroom;

/**
 * Record-only classroom discipline snapshot (05A §5.3).
 *
 * @param level            discipline level 0-3
 * @param distractedCount  number of students currently distracted
 * @param whisperingCount  number of students currently whispering
 * @param noisyRatio       {@code (distracted + whispering) / total students}
 * @param avgAttention     mean attention across all students
 */
public record DisciplineSnapshot(
        int level,
        int distractedCount,
        int whisperingCount,
        double noisyRatio,
        double avgAttention) {
}
