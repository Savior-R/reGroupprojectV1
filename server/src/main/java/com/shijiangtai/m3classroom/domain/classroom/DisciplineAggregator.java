package com.shijiangtai.m3classroom.domain.classroom;

import java.util.List;
import java.util.Objects;

/**
 * Pure classroom discipline aggregation (05A §5.3).
 *
 * <p>The level is the larger of the count-based and ratio-based level, so both small and large
 * classes behave sensibly. A level 2 that has persisted for more than 20 seconds escalates to
 * level 3. The aggregator never mutates students and its result is never fed back into virtual
 * student probabilities.
 */
public final class DisciplineAggregator {

    private static final long LEVEL_TWO_ESCALATION_MS = 20_000L;
    private static final double MILD_RATIO = 0.10;
    private static final double NOISY_RATIO = 0.25;
    private static final double CHAOTIC_RATIO = 0.50;

    public DisciplineSnapshot aggregate(
            List<VirtualStudentRuntime> students, long levelDurationMs) {
        Objects.requireNonNull(students, "students");

        int distractedCount = 0;
        int whisperingCount = 0;
        double attentionSum = 0.0;
        for (VirtualStudentRuntime student : students) {
            switch (student.behavior()) {
                case DISTRACTED -> distractedCount++;
                case WHISPERING -> whisperingCount++;
                default -> {
                    // other behaviours do not contribute to discipline
                }
            }
            attentionSum += student.attention();
        }

        int total = students.size();
        int noisyCount = distractedCount + whisperingCount;
        double noisyRatio = total == 0 ? 0.0 : (double) noisyCount / total;
        double avgAttention = total == 0 ? 0.0 : attentionSum / total;

        DisciplineLevel level = DisciplineLevel.max(
                countLevel(distractedCount, whisperingCount),
                ratioLevel(noisyRatio));
        if (level == DisciplineLevel.NOISY && levelDurationMs > LEVEL_TWO_ESCALATION_MS) {
            level = DisciplineLevel.CHAOTIC;
        }
        return new DisciplineSnapshot(
                level.level(), distractedCount, whisperingCount, noisyRatio, avgAttention);
    }

    private static DisciplineLevel countLevel(int distractedCount, int whisperingCount) {
        int noisyCount = distractedCount + whisperingCount;
        if (noisyCount >= 6) {
            return DisciplineLevel.CHAOTIC;
        }
        if (noisyCount >= 3) {
            return DisciplineLevel.NOISY;
        }
        if (noisyCount == 2 || (noisyCount == 1 && whisperingCount == 1)) {
            return DisciplineLevel.MILD;
        }
        return DisciplineLevel.QUIET;
    }

    private static DisciplineLevel ratioLevel(double noisyRatio) {
        if (noisyRatio > CHAOTIC_RATIO) {
            return DisciplineLevel.CHAOTIC;
        }
        if (noisyRatio > NOISY_RATIO) {
            return DisciplineLevel.NOISY;
        }
        if (noisyRatio >= MILD_RATIO) {
            return DisciplineLevel.MILD;
        }
        return DisciplineLevel.QUIET;
    }
}
