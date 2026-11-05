package com.shijiangtai.m3classroom.domain.classroom;

import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Virtual-student dynamics: attention, hand-raise energy, autonomous behaviour and the
 * record-only classroom discipline aggregation (05A §5.1/§5.3).
 *
 * <p>Event deltas use the matrix in 05A §5.3 with a per-event noise of {@code ±20%}, but never
 * less than {@code ±0.005}. The noise source is injected through the constructor so tests can
 * seed it deterministically; {@link #tick(long, Instant, RandomGenerator)} additionally receives
 * the caller's random generator for autonomous discipline decisions.
 *
 * <p>Discipline aggregation is record-only: it never changes attention, energy or behaviour.
 * The only events produced by this class are {@code student.state_changed} (behaviour
 * transitions caused by a tick or by teacher management) and
 * {@code classroom.discipline_level_changed} (level changes detected by
 * {@link #disciplineLevelChangedIfNeeded(Instant)}).
 */
public final class VirtualStudentDynamics {

    public static final String STUDENT_STATE_CHANGED = "student.state_changed";
    public static final String DISCIPLINE_LEVEL_CHANGED =
            "classroom.discipline_level_changed";

    private static final double EVENT_NOISE_RATIO = 0.20;
    private static final double MIN_EVENT_NOISE = 0.005;

    private static final double QUESTION_ASKED_ATTENTION = 0.05;
    private static final double CALLED_ON_ATTENTION = 0.15;
    private static final double CALLED_ON_ENERGY = 0.05;
    private static final double OTHER_STUDENT_CALLED_ON_ATTENTION = -0.02;
    private static final double MARKED_CORRECT_ATTENTION = 0.10;
    private static final double MARKED_CORRECT_ENERGY = 0.05;
    private static final double MARKED_PARTIAL_ATTENTION = 0.02;
    private static final double MARKED_WRONG_ATTENTION = -0.10;
    private static final double MANAGED_ATTENTION = 0.25;
    private static final double HAND_RAISED_ATTENTION = 0.05;
    private static final double HAND_RAISED_ENERGY = -0.20;
    private static final double STUDENT_QUESTION_ATTENTION = 0.10;
    private static final double STUDENT_QUESTION_ENERGY = 0.05;
    private static final double ANSWER_ATTENTION = 0.10;
    private static final double FOLLOW_UP_ATTENTION = 0.05;
    private static final double WHISPER_CONTAGION_BONUS = 0.02;
    private static final double DISTRACTED_ATTENTION = -0.05;
    private static final double DISTRACTED_ENERGY = -0.05;
    private static final double DISTRACTED_NEIGHBOUR_ATTENTION = -0.02;
    private static final double WHISPERING_ATTENTION = -0.08;
    private static final double WHISPERING_ENERGY = -0.05;
    private static final double WHISPERING_NEIGHBOUR_ATTENTION = -0.03;
    private static final double DISCIPLINE_EVENT_ATTENTION = -0.10;
    private static final double DISCIPLINE_EVENT_NEIGHBOUR_ATTENTION = -0.03;
    private static final double QA_STARTED_ATTENTION = 0.03;
    private static final double HAND_WINDOW_TIMEOUT_ATTENTION = -0.02;

    private final RandomGenerator eventRandom;
    private final DisciplineAggregator aggregator = new DisciplineAggregator();
    private final List<VirtualStudentRuntime> students = new ArrayList<>();

    private int disciplineLevel = DisciplineLevel.QUIET.level();
    private int rawDisciplineLevel = DisciplineLevel.QUIET.level();
    private Instant rawDisciplineLevelSince;

    public VirtualStudentDynamics() {
        // java.util.Random lives in java.base; the Alpine JRE used by the Docker image does not
        // ship the jdk.random module required by RandomGenerator.getDefault().
        this(new Random());
    }

    public VirtualStudentDynamics(RandomGenerator eventRandom) {
        this.eventRandom = Objects.requireNonNull(eventRandom, "eventRandom");
    }

    /** Replaces the runtime students from their static configuration. */
    public void initialize(List<VirtualStudentConfig> configs) {
        Objects.requireNonNull(configs, "configs");
        students.clear();
        for (VirtualStudentConfig config : configs) {
            students.add(new VirtualStudentRuntime(Objects.requireNonNull(config, "config")));
        }
        disciplineLevel = DisciplineLevel.QUIET.level();
        rawDisciplineLevel = DisciplineLevel.QUIET.level();
        rawDisciplineLevelSince = null;
    }

    /** Immutable view of the runtime students. */
    public List<VirtualStudentRuntime> students() {
        return List.copyOf(students);
    }

    public VirtualStudentRuntime find(UUID studentId) {
        Objects.requireNonNull(studentId, "studentId");
        for (VirtualStudentRuntime student : students) {
            if (student.id().equals(studentId)) {
                return student;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                "虚拟学生不存在",
                Map.of("student_id", studentId.toString()));
    }

    /**
     * Advances every student and returns the resulting behaviour transitions followed by a
     * discipline level change when the aggregation level changed.
     */
    public List<ClassroomEvent> tick(long elapsedSeconds, Instant now, RandomGenerator random) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(random, "random");
        List<ClassroomEvent> events = new ArrayList<>();
        List<VirtualStudentRuntime> newWhisperers = new ArrayList<>();
        for (VirtualStudentRuntime student : students) {
            VirtualStudentBehavior before = student.behavior();
            student.tick(elapsedSeconds, random);
            VirtualStudentBehavior after = student.behavior();
            if (before != after) {
                String reason = after == VirtualStudentBehavior.FOCUSED
                        ? "state_duration_elapsed"
                        : "low_attention";
                events.add(stateChanged(student, before, after, reason));
                if (after == VirtualStudentBehavior.WHISPERING) {
                    newWhisperers.add(student);
                }
            }
        }
        for (VirtualStudentRuntime whisperer : newWhisperers) {
            applyWhisperContagionToNeighbours(whisperer);
        }
        events.addAll(disciplineLevelChangedIfNeeded(now));
        return List.copyOf(events);
    }

    /** {@code classroom.question_asked}: the whole class gains attention. */
    public void applyQuestionAsked() {
        for (VirtualStudentRuntime student : students) {
            applyNoisyAttention(student, QUESTION_ASKED_ATTENTION);
        }
    }

    /** {@code student.question}: the asking student gains attention and hand-raise energy. */
    public void applyStudentQuestion(UUID studentId) {
        VirtualStudentRuntime student = find(studentId);
        applyNoisyAttention(student, STUDENT_QUESTION_ATTENTION);
        applyNoisyEnergy(student, STUDENT_QUESTION_ENERGY);
    }

    /**
     * {@code teacher.call_on}: the called student gains, everybody else loses a little, and the
     * called student enters {@code called_on} (05A §5.2).
     *
     * @return the persisted {@code student.state_changed} transition, or an empty list when the
     *     student was already {@code called_on}
     */
    public List<ClassroomEvent> applyCalledOn(UUID studentId) {
        VirtualStudentRuntime called = find(studentId);
        applyNoisyAttention(called, CALLED_ON_ATTENTION);
        applyNoisyEnergy(called, CALLED_ON_ENERGY);
        for (VirtualStudentRuntime student : students) {
            if (!student.id().equals(called.id())) {
                applyNoisyAttention(student, OTHER_STUDENT_CALLED_ON_ATTENTION);
            }
        }
        return transition(called, VirtualStudentBehavior.CALLED_ON, "called_on");
    }

    /** {@code teacher.mark_answer}: attention/energy depend on correctness. */
    public void applyMarked(UUID studentId, Correctness correctness) {
        Objects.requireNonNull(correctness, "correctness");
        VirtualStudentRuntime student = find(studentId);
        switch (correctness) {
            case CORRECT -> {
                applyNoisyAttention(student, MARKED_CORRECT_ATTENTION);
                applyNoisyEnergy(student, MARKED_CORRECT_ENERGY);
            }
            case PARTIAL -> applyNoisyAttention(student, MARKED_PARTIAL_ATTENTION);
            case WRONG -> applyNoisyAttention(student, MARKED_WRONG_ATTENTION);
        }
    }

    /**
     * {@code teacher.manage_student}: fixed {@code +0.25} attention delta and, when the student
     * was distracted or whispering, a return to {@code FOCUSED} with a
     * {@code student.state_changed {reason: teacher_manage}} event.
     */
    public List<ClassroomEvent> applyManaged(UUID studentId) {
        VirtualStudentRuntime student = find(studentId);
        applyNoisyAttention(student, MANAGED_ATTENTION);
        VirtualStudentBehavior before = student.behavior();
        if (before == VirtualStudentBehavior.DISTRACTED
                || before == VirtualStudentBehavior.WHISPERING) {
            student.changeBehavior(VirtualStudentBehavior.FOCUSED);
            return List.of(stateChanged(
                    student, before, VirtualStudentBehavior.FOCUSED, "teacher_manage"));
        }
        return List.of();
    }

    /**
     * {@code student.hand_raised}: noisy attention {@code +0.05}, noisy energy {@code -0.20}, a
     * fresh 45 s cooldown and the {@code hand_raised} behaviour state. This is the single
     * application point for the event; both deltas use the supplied random generator.
     *
     * @return the persisted {@code student.state_changed} transition, or an empty list when the
     *     student was already {@code hand_raised}
     */
    public List<ClassroomEvent> applyHandRaised(
            UUID studentId, Instant now, RandomGenerator random) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(random, "random");
        VirtualStudentRuntime student = find(studentId);
        double energyDelta = noisy(HAND_RAISED_ENERGY, random);
        student.applyAttentionDelta(noisy(HAND_RAISED_ATTENTION, random));
        student.markHandRaised(now, energyDelta);
        return transition(student, VirtualStudentBehavior.HAND_RAISED, "hand_raised");
    }

    /**
     * Lowers an open hand and returns the student to {@code focused} (05A §5.2: 30 s without a
     * call-on plus timeout). Non-raised students are left untouched, so a command that lowers a
     * hand after another transition cannot corrupt the newer state.
     */
    public List<ClassroomEvent> applyHandLowered(UUID studentId, String reason) {
        Objects.requireNonNull(reason, "reason");
        VirtualStudentRuntime student = find(studentId);
        if (student.behavior() != VirtualStudentBehavior.HAND_RAISED) {
            return List.of();
        }
        return transition(student, VirtualStudentBehavior.FOCUSED, reason);
    }

    /**
     * {@code student.answer}: answering student gains attention and enters {@code answering}
     * (05A §5.2).
     */
    public List<ClassroomEvent> applyAnswer(UUID studentId) {
        VirtualStudentRuntime student = find(studentId);
        applyNoisyAttention(student, ANSWER_ATTENTION);
        return transition(student, VirtualStudentBehavior.ANSWERING, "answer_started");
    }

    /**
     * An answer finished (no follow-up in P0): the student returns to {@code focused} (05A §5.2).
     */
    public List<ClassroomEvent> applyAnswerFinished(UUID studentId) {
        VirtualStudentRuntime student = find(studentId);
        if (student.behavior() == VirtualStudentBehavior.FOCUSED) {
            return List.of();
        }
        return transition(student, VirtualStudentBehavior.FOCUSED, "answer_finished");
    }

    /**
     * {@code student.follow_up}: the following-up student gains noisy attention and enters
     * {@code dialogue}. A follow-up while already in {@code dialogue} only applies the attention
     * delta.
     *
     * @return the persisted {@code student.state_changed} transition, or an empty list when the
     *     student was already {@code dialogue}
     */
    public List<ClassroomEvent> applyFollowUp(UUID studentId) {
        VirtualStudentRuntime student = find(studentId);
        applyNoisyAttention(student, FOLLOW_UP_ATTENTION);
        return transition(student, VirtualStudentBehavior.DIALOGUE, "follow_up");
    }

    /**
     * Ends the current dialogue: the student returns to {@code focused}.
     *
     * @return the persisted {@code student.state_changed} transition, or an empty list when the
     *     student was already {@code focused}
     */
    public List<ClassroomEvent> applyDialogueEnded(UUID studentId) {
        return transition(
                find(studentId), VirtualStudentBehavior.FOCUSED, "dialogue_ended");
    }

    /**
     * Applies an externally driven {@code student.state_changed} event: the transition itself
     * plus the discipline deltas from 05A §5.3 (distracted/whispering also affect neighbours).
     */
    public void applyStateChanged(UUID studentId, VirtualStudentBehavior behavior) {
        Objects.requireNonNull(behavior, "behavior");
        VirtualStudentRuntime student = find(studentId);
        VirtualStudentBehavior before = student.behavior();
        student.changeBehavior(behavior);
        switch (behavior) {
            case DISTRACTED -> {
                applyNoisyAttention(student, DISTRACTED_ATTENTION);
                applyNoisyEnergy(student, DISTRACTED_ENERGY);
                applyToNeighbours(student, DISTRACTED_NEIGHBOUR_ATTENTION);
            }
            case WHISPERING -> {
                applyNoisyAttention(student, WHISPERING_ATTENTION);
                applyNoisyEnergy(student, WHISPERING_ENERGY);
                applyToNeighbours(student, WHISPERING_NEIGHBOUR_ATTENTION);
                if (before != VirtualStudentBehavior.WHISPERING) {
                    applyWhisperContagionToNeighbours(student);
                }
            }
            default -> {
                // other transitions carry no attention/energy delta
            }
        }
    }

    /** {@code discipline.event}: the student loses attention, neighbours lose a little. */
    public void applyDisciplineEvent(UUID studentId) {
        VirtualStudentRuntime student = find(studentId);
        applyNoisyAttention(student, DISCIPLINE_EVENT_ATTENTION);
        applyToNeighbours(student, DISCIPLINE_EVENT_NEIGHBOUR_ATTENTION);
    }

    /** {@code classroom.state_changed -> qa}: the whole class gains attention. */
    public void applyQaStarted() {
        for (VirtualStudentRuntime student : students) {
            applyNoisyAttention(student, QA_STARTED_ATTENTION);
        }
    }

    /** {@code classroom.hand_window_timeout}: the whole class loses attention. */
    public void applyHandWindowTimeout() {
        for (VirtualStudentRuntime student : students) {
            applyNoisyAttention(student, HAND_WINDOW_TIMEOUT_ATTENTION);
        }
    }

    /** Current record-only discipline level, 0-3. */
    public int disciplineLevel() {
        return disciplineLevel;
    }

    /**
     * Aggregates the current behaviour states and emits {@code classroom.discipline_level_changed}
     * only when the effective level changed. This is record-only: students are never mutated.
     *
     * <p>The raw aggregate level and the instant it last changed are tracked separately from the
     * effective (possibly escalated) level. A raw level 2 escalates to 3 once the raw level has
     * persisted for more than 20 s, and the raw-duration clock is <em>not</em> reset by that
     * escalation; otherwise the next aggregation would see raw level 2 with a fresh duration and
     * emit a spurious 3 -> 2 flap. The effective level drops normally as soon as the raw level
     * falls to 1 or 0.
     */
    public List<ClassroomEvent> disciplineLevelChangedIfNeeded(Instant now) {
        Objects.requireNonNull(now, "now");
        int rawLevel = aggregator.aggregate(students, 0L).level();
        long durationMs;
        if (rawDisciplineLevelSince == null || rawLevel != rawDisciplineLevel) {
            rawDisciplineLevel = rawLevel;
            rawDisciplineLevelSince = now;
            durationMs = 0L;
        } else {
            durationMs = Math.max(0L,
                    Duration.between(rawDisciplineLevelSince, now).toMillis());
        }

        DisciplineSnapshot snapshot = aggregator.aggregate(students, durationMs);
        int effectiveLevel = snapshot.level();
        if (effectiveLevel == disciplineLevel) {
            return List.of();
        }

        int from = disciplineLevel;
        disciplineLevel = effectiveLevel;
        String reason = rawLevel == DisciplineLevel.NOISY.level()
                && effectiveLevel == DisciplineLevel.CHAOTIC.level()
                ? "level_duration"
                : "aggregate";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("to", effectiveLevel);
        payload.put("distracted_count", snapshot.distractedCount());
        payload.put("whispering_count", snapshot.whisperingCount());
        payload.put("noisy_ratio", snapshot.noisyRatio());
        payload.put("avg_attention", snapshot.avgAttention());
        payload.put("duration_ms", durationMs);
        payload.put("reason", reason);
        return List.of(new ClassroomEvent(DISCIPLINE_LEVEL_CHANGED, payload));
    }

    private void applyToNeighbours(VirtualStudentRuntime target, double attentionDelta) {
        int seatNo = target.config().seatNo();
        for (VirtualStudentRuntime student : students) {
            if (student.id().equals(target.id())) {
                continue;
            }
            if (Math.abs(student.config().seatNo() - seatNo) == 1) {
                applyNoisyAttention(student, attentionDelta);
            }
        }
    }

    private void applyWhisperContagionToNeighbours(VirtualStudentRuntime whisperer) {
        int seatNo = whisperer.config().seatNo();
        for (VirtualStudentRuntime student : students) {
            if (student.id().equals(whisperer.id())) {
                continue;
            }
            if (Math.abs(student.config().seatNo() - seatNo) == 1) {
                student.applyWhisperContagionBonus(WHISPER_CONTAGION_BONUS);
            }
        }
    }

    private void applyNoisyAttention(VirtualStudentRuntime student, double delta) {
        if (delta != 0.0) {
            student.applyAttentionDelta(noisy(delta));
        }
    }

    private void applyNoisyEnergy(VirtualStudentRuntime student, double delta) {
        if (delta != 0.0) {
            student.applyEnergyDelta(noisy(delta));
        }
    }

    private double noisy(double delta) {
        return noisy(delta, eventRandom);
    }

    private double noisy(double delta, RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        double amplitude = Math.max(Math.abs(delta) * EVENT_NOISE_RATIO, MIN_EVENT_NOISE);
        return delta + (random.nextDouble() * 2.0 - 1.0) * amplitude;
    }

    /**
     * Applies one externally driven behaviour transition through {@link #applyStateChanged} so the
     * 05A §5.3 state-scoped deltas stay in one place, and returns the matching
     * {@code student.state_changed} event. A no-op transition returns an empty list.
     */
    private List<ClassroomEvent> transition(
            VirtualStudentRuntime student, VirtualStudentBehavior to, String reason) {
        VirtualStudentBehavior from = student.behavior();
        if (from == to) {
            return List.of();
        }
        applyStateChanged(student.id(), to);
        return List.of(stateChanged(student, from, to, reason));
    }

    private static ClassroomEvent stateChanged(
            VirtualStudentRuntime student,
            VirtualStudentBehavior from,
            VirtualStudentBehavior to,
            String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("student_id", student.id());
        payload.put("from", from.wireValue());
        payload.put("to", to.wireValue());
        payload.put("reason", reason);
        return new ClassroomEvent(STUDENT_STATE_CHANGED, payload);
    }
}
