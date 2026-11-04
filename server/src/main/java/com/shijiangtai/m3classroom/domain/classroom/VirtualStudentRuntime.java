package com.shijiangtai.m3classroom.domain.classroom;

import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Mutable in-memory state of one virtual student (05A §5.1).
 *
 * <p>Static parameters come from {@link VirtualStudentConfig}; attention, hand-raise energy,
 * cooldown and behaviour live only in the {@code SessionActor} memory (01 §"运行时状态不落库").
 *
 * <p>Each call to {@link #tick(long, RandomGenerator)} applies one elapsed interval:
 * <pre>
 * decay_rate = 0.002 + 0.004 * (1 - attention_baseline)
 * attention  = clamp(attention - decay_rate * seconds, 0.05, 1)
 * energy     = clamp(energy + 0.005 * seconds, 0, 1)
 * </pre>
 * While attention is below 0.35 a focused student can become distracted or whisper; the
 * resulting behaviour lasts 10-40 seconds.
 */
public final class VirtualStudentRuntime {

    public static final double MIN_ATTENTION = 0.05;
    public static final double MAX_ATTENTION = 1.0;
    public static final double MIN_HAND_RAISE_ENERGY = 0.0;
    public static final double MAX_HAND_RAISE_ENERGY = 1.0;
    public static final double LOW_ATTENTION_THRESHOLD = 0.35;

    private static final double BASE_DECAY_PER_SECOND = 0.002;
    private static final double BASELINE_DECAY_PER_SECOND = 0.004;
    private static final double ENERGY_RECOVERY_PER_SECOND = 0.005;
    private static final double HAND_RAISE_ENERGY_COST = 0.20;
    private static final double STATE_FACTOR = 1.0;
    private static final long MIN_DISCIPLINE_SECONDS = 10;
    private static final long MAX_DISCIPLINE_SECONDS = 40;
    private static final Duration HAND_RAISE_COOLDOWN = Duration.ofSeconds(45);

    private final VirtualStudentConfig config;
    private double attention;
    private double handRaiseEnergy = MAX_HAND_RAISE_ENERGY;
    private Instant cooldownUntil;
    private VirtualStudentBehavior behavior = VirtualStudentBehavior.FOCUSED;
    private long behaviorRemainingSeconds;

    public VirtualStudentRuntime(VirtualStudentConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.attention = config.attentionBaseline().doubleValue();
    }

    public VirtualStudentConfig config() {
        return config;
    }

    public UUID id() {
        return config.id();
    }

    public double attention() {
        return attention;
    }

    public double handRaiseEnergy() {
        return handRaiseEnergy;
    }

    /** End of the current 45 s hand-raise cooldown, or {@code null} if never raised. */
    public Instant cooldownUntil() {
        return cooldownUntil;
    }

    public VirtualStudentBehavior behavior() {
        return behavior;
    }

    /** Per-second attention decay derived from the static baseline. */
    public double decayRate() {
        return BASE_DECAY_PER_SECOND
                + BASELINE_DECAY_PER_SECOND * (1.0 - config.attentionBaseline().doubleValue());
    }

    public boolean isCoolingDown(Instant now) {
        Objects.requireNonNull(now, "now");
        return cooldownUntil != null && now.isBefore(cooldownUntil);
    }

    /** Hand-raise candidates are focused or distracted and outside the 45 s cooldown. */
    public boolean canRaiseHand(Instant now) {
        return (behavior == VirtualStudentBehavior.FOCUSED
                || behavior == VirtualStudentBehavior.DISTRACTED)
                && !isCoolingDown(now);
    }

    /**
     * Advances attention, hand-raise energy and autonomous discipline behaviour by the given
     * number of seconds.
     */
    public void tick(long elapsedSeconds, RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        if (elapsedSeconds < 0) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "tick 时长不能为负",
                    Map.of("field", "elapsed_seconds", "value", elapsedSeconds));
        }
        if (elapsedSeconds == 0) {
            return;
        }
        attention = clamp(attention - decayRate() * elapsedSeconds,
                MIN_ATTENTION, MAX_ATTENTION);
        handRaiseEnergy = clamp(handRaiseEnergy + ENERGY_RECOVERY_PER_SECOND * elapsedSeconds,
                MIN_HAND_RAISE_ENERGY, MAX_HAND_RAISE_ENERGY);

        if (behaviorRemainingSeconds > 0) {
            behaviorRemainingSeconds -= elapsedSeconds;
            if (behaviorRemainingSeconds <= 0) {
                behaviorRemainingSeconds = 0;
                behavior = VirtualStudentBehavior.FOCUSED;
            }
            return;
        }
        if (behavior == VirtualStudentBehavior.FOCUSED
                && attention < LOW_ATTENTION_THRESHOLD) {
            double probability = config.disciplineProbability().doubleValue()
                    * (1.0 - attention) * STATE_FACTOR;
            if (random.nextDouble() < probability) {
                behavior = random.nextDouble() < 0.5
                        ? VirtualStudentBehavior.DISTRACTED
                        : VirtualStudentBehavior.WHISPERING;
                long duration = MIN_DISCIPLINE_SECONDS + (long) (random.nextDouble()
                        * (MAX_DISCIPLINE_SECONDS - MIN_DISCIPLINE_SECONDS + 1));
                behaviorRemainingSeconds = Math.min(MAX_DISCIPLINE_SECONDS, duration);
            }
        }
    }

    public void applyAttentionDelta(double delta) {
        attention = clamp(attention + delta, MIN_ATTENTION, MAX_ATTENTION);
    }

    public void applyEnergyDelta(double delta) {
        handRaiseEnergy = clamp(handRaiseEnergy + delta,
                MIN_HAND_RAISE_ENERGY, MAX_HAND_RAISE_ENERGY);
    }

    /**
     * Applies the hand-raise cost: energy {@code -0.20} and a fresh 45 s cooldown. The
     * corresponding {@code student.hand_raised} attention delta is applied by
     * {@link VirtualStudentDynamics#applyHandRaised(UUID)} so this event is applied exactly once.
     */
    public void markHandRaised(Instant now) {
        Objects.requireNonNull(now, "now");
        applyEnergyDelta(-HAND_RAISE_ENERGY_COST);
        cooldownUntil = now.plus(HAND_RAISE_COOLDOWN);
    }

    /** Sets the behaviour directly; an externally driven behaviour has no autonomous expiry. */
    public void changeBehavior(VirtualStudentBehavior behavior) {
        this.behavior = Objects.requireNonNull(behavior, "behavior");
        this.behaviorRemainingSeconds = 0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
