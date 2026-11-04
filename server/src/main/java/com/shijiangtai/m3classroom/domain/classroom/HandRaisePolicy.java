package com.shijiangtai.m3classroom.domain.classroom;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Selection-only hand-raise policy for a qa hand window (05A §5.4).
 *
 * <p>Candidates are the students that are {@code focused} or {@code distracted} and therefore
 * not in a dialogue or any other interaction. Students in the 45 s hand-raise cooldown are
 * skipped unless every candidate is cooling down, in which case the cooldown is ignored and the
 * single highest-scoring student is forced, as required by "保底至少 1 人".
 *
 * <p>The score is exactly
 * {@code activity*0.3 + question_rate*0.2 + attention*0.2 + hand_raise_energy*0.2 + random*0.1}.
 * Up to {@value #MAX_HAND_RAISES} students strictly above
 * {@value #SELECTION_THRESHOLD} are selected; when nobody crosses the threshold the single
 * highest score is selected instead.
 *
 * <p><b>This class must not mutate runtime state.</b> It does not apply the hand-raise energy
 * cost or cooldown; the caller emits {@code student.hand_raised} and then calls
 * {@link VirtualStudentDynamics#applyHandRaised(UUID, Instant, RandomGenerator)} exactly once,
 * which owns the noisy {@code -0.20} energy delta and the 45 s cooldown.
 */
public final class HandRaisePolicy {

    public static final int MAX_HAND_RAISES = 3;
    public static final double SELECTION_THRESHOLD = 0.5;

    private static final double ACTIVITY_WEIGHT = 0.3;
    private static final double QUESTION_RATE_WEIGHT = 0.2;
    private static final double ATTENTION_WEIGHT = 0.2;
    private static final double HAND_RAISE_ENERGY_WEIGHT = 0.2;
    private static final double RANDOM_WEIGHT = 0.1;

    public List<HandRaise> select(
            List<VirtualStudentRuntime> candidates, Instant now, RandomGenerator random) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(random, "random");

        List<VirtualStudentRuntime> eligible = candidates.stream()
                .filter(HandRaisePolicy::canBeSelected)
                .toList();
        if (eligible.isEmpty()) {
            return List.of();
        }

        List<VirtualStudentRuntime> notCoolingDown = eligible.stream()
                .filter(candidate -> !candidate.isCoolingDown(now))
                .toList();
        boolean cooldownIgnored = notCoolingDown.isEmpty();
        List<VirtualStudentRuntime> pool = cooldownIgnored ? eligible : notCoolingDown;

        List<ScoredStudent> scored = new ArrayList<>(pool.size());
        for (VirtualStudentRuntime candidate : pool) {
            scored.add(new ScoredStudent(candidate, score(candidate, random.nextDouble())));
        }
        scored.sort(Comparator.comparingDouble(ScoredStudent::score).reversed()
                .thenComparingInt(candidate -> candidate.student().config().seatNo()));

        if (cooldownIgnored) {
            return List.of(toHandRaise(scored.get(0).student()));
        }
        List<VirtualStudentRuntime> aboveThreshold = scored.stream()
                .filter(candidate -> candidate.score() > SELECTION_THRESHOLD)
                .limit(MAX_HAND_RAISES)
                .map(ScoredStudent::student)
                .toList();
        if (aboveThreshold.isEmpty()) {
            return List.of(toHandRaise(scored.get(0).student()));
        }
        return aboveThreshold.stream().map(HandRaisePolicy::toHandRaise).toList();
    }

    private static boolean canBeSelected(VirtualStudentRuntime student) {
        // DIALOGUE and every other interaction behaviour is excluded by this check.
        return student.behavior() == VirtualStudentBehavior.FOCUSED
                || student.behavior() == VirtualStudentBehavior.DISTRACTED;
    }

    private static double score(VirtualStudentRuntime student, double randomDraw) {
        return student.config().activity().doubleValue() * ACTIVITY_WEIGHT
                + student.config().questionRate().doubleValue() * QUESTION_RATE_WEIGHT
                + student.attention() * ATTENTION_WEIGHT
                + student.handRaiseEnergy() * HAND_RAISE_ENERGY_WEIGHT
                + randomDraw * RANDOM_WEIGHT;
    }

    private static HandRaise toHandRaise(VirtualStudentRuntime student) {
        return new HandRaise(UUID.randomUUID(), student.id(), null);
    }

    private record ScoredStudent(VirtualStudentRuntime student, double score) {
    }
}
