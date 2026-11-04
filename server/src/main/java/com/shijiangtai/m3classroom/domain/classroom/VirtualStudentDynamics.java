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
 * transitions caused by a tick) and {@code classroom.discipline_level_changed} (level changes
 * detected by {@link #disciplineLevelChangedIfNeeded(Instant)}).
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
    private static final double ANSWER_ATTENTION = 0.10;
    private static final double FOLLOW_UP_ATTENTION = 0.05;
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
    private Instant disciplineLevelSince;

    public VirtualStudentDynamics() {
        this(RandomGenerator.getDefault());
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
        disciplineLevelSince = null;
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
        for (VirtualStudentRuntime student : students) {
            VirtualStudentBehavior before = student.behavior();
            student.tick(elapsedSeconds, random);
            VirtualStudentBehavior after = student.behavior();
            if (before != after) {
                String reason = after == VirtualStudentBehavior.FOCUSED
                        ? "state_duration_elapsed"
                        : "low_attention";
                events.add(stateChanged(student, before, after, reason));
            }
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

    /** {@code teacher.call_on}: the called student gains, everybody else loses a little. */
    public void applyCalledOn(UUID studentId) {
        VirtualStudentRuntime called = find(studentId);
        applyNoisyAttention(called, CALLED_ON_ATTENTION);
        applyNoisyEnergy(called, CALLED_ON_ENERGY);
        for (VirtualStudentRuntime student : students) {
            if (!student.id().equals(called.id())) {
                applyNoisyAttention(student, OTHER_STUDENT_CALLED_ON_ATTENTION);
            }
        }
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

    /** {@code teacher.manage_student}: fixed {@code +0.25} attention delta. */
    public void applyManaged(UUID studentId) {
        applyNoisyAttention(find(studentId), MANAGED_ATTENTION);
    }

    /**
     * {@code student.hand_raised}: attention {@code +0.05}. The energy cost and 45 s cooldown
     * belong to {@link VirtualStudentRuntime#markHandRaised(Instant)} so the event is applied
     * exactly once.
     */
    public void applyHandRaised(UUID studentId) {
        applyNoisyAttention(find(studentId), HAND_RAISED_ATTENTION);
    }

    /** {@code student.answer}: answering student gains attention. */
    public void applyAnswer(UUID studentId) {
        applyNoisyAttention(find(studentId), ANSWER_ATTENTION);
    }

    /** {@code student.follow_up}: following-up student gains attention. */
    public void applyFollowUp(UUID studentId) {
        applyNoisyAttention(find(studentId), FOLLOW_UP_ATTENTION);
    }

    /**
     * Applies an externally driven {@code student.state_changed} event: the transition itself
     * plus the discipline deltas from 05A §5.3 (distracted/whispering also affect neighbours).
     */
    public void applyStateChanged(UUID studentId, VirtualStudentBehavior behavior) {
        Objects.requireNonNull(behavior, "behavior");
        VirtualStudentRuntime student = find(studentId);
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
     * only when the level changed. This is record-only: students are never mutated.
     */
    public List<ClassroomEvent> disciplineLevelChangedIfNeeded(Instant now) {
        Objects.requireNonNull(now, "now");
        long durationMs = disciplineLevelSince == null
                ? 0L
                : Math.max(0L, Duration.between(disciplineLevelSince, now).toMillis());
        DisciplineSnapshot snapshot = aggregator.aggregate(students, durationMs);
        if (disciplineLevelSince == null) {
            disciplineLevelSince = now;
        }
        if (snapshot.level() == disciplineLevel) {
            return List.of();
        }

        int rawLevel = aggregator.aggregate(students, 0L).level();
        String reason = rawLevel == DisciplineLevel.NOISY.level()
                && snapshot.level() == DisciplineLevel.CHAOTIC.level()
                ? "level_duration"
                : "aggregate";

        int from = disciplineLevel;
        disciplineLevel = snapshot.level();
        disciplineLevelSince = now;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("to", snapshot.level());
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
        double amplitude = Math.max(Math.abs(delta) * EVENT_NOISE_RATIO, MIN_EVENT_NOISE);
        return delta + (eventRandom.nextDouble() * 2.0 - 1.0) * amplitude;
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
