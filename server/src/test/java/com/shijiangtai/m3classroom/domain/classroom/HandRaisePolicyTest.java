package com.shijiangtai.m3classroom.domain.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class HandRaisePolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final HandRaisePolicy policy = new HandRaisePolicy();

    @Test
    void selectsOneToThreeStudents() {
        List<VirtualStudentRuntime> candidates = List.of(
                student(1, "1.00", "1.00", "1.00"),
                student(2, "0.90", "1.00", "1.00"),
                student(3, "0.80", "1.00", "1.00"),
                student(4, "0.70", "1.00", "1.00"),
                student(5, "0.60", "1.00", "1.00"));

        List<HandRaise> handRaises = policy.select(
                candidates, NOW, new ScriptedRandom(0.9, 0.9, 0.9, 0.9, 0.9));

        assertThat(handRaises).hasSize(3);
        assertThat(handRaises).extracting(HandRaise::studentId)
                .containsExactly(studentId(1), studentId(2), studentId(3));
        assertThat(handRaises).extracting(HandRaise::handRaiseId)
                .doesNotContainNull()
                .doesNotHaveDuplicates();
    }

    @Test
    void guaranteesAtLeastOneWhenAllBelowThreshold() {
        List<VirtualStudentRuntime> candidates = List.of(
                student(1, "0.00", "0.00", "0.00"),
                student(2, "0.50", "0.00", "0.00"),
                student(3, "0.00", "0.00", "0.00"));

        List<HandRaise> handRaises = policy.select(
                candidates, NOW, new ScriptedRandom(0.0, 0.0, 0.0));

        assertThat(handRaises).hasSize(1);
        assertThat(handRaises.get(0).studentId()).isEqualTo(studentId(2));
    }

    @Test
    void ignoresCooldownIfAllCoolingDown() {
        VirtualStudentRuntime best = student(1, "1.00", "1.00", "1.00");
        VirtualStudentRuntime other = student(2, "0.00", "0.00", "0.00");
        best.markHandRaised(NOW, 0.0);
        other.markHandRaised(NOW, 0.0);
        assertThat(best.isCoolingDown(NOW)).isTrue();
        assertThat(other.isCoolingDown(NOW)).isTrue();

        List<HandRaise> handRaises = policy.select(
                List.of(best, other), NOW, new ScriptedRandom(0.0, 0.0));

        assertThat(handRaises).hasSize(1);
        assertThat(handRaises.get(0).studentId()).isEqualTo(studentId(1));
    }

    @Test
    void respectsFocusedOrDistractedAndNotInDialogue() {
        VirtualStudentRuntime focused = student(1, "1.00", "1.00", "1.00");
        VirtualStudentRuntime distracted = student(2, "0.90", "1.00", "1.00");
        VirtualStudentRuntime whispering = student(3, "1.00", "1.00", "1.00");
        VirtualStudentRuntime handRaised = student(4, "1.00", "1.00", "1.00");
        VirtualStudentRuntime calledOn = student(5, "1.00", "1.00", "1.00");
        VirtualStudentRuntime answering = student(6, "1.00", "1.00", "1.00");
        VirtualStudentRuntime inDialogue = student(7, "1.00", "1.00", "1.00");
        distracted.changeBehavior(VirtualStudentBehavior.DISTRACTED);
        whispering.changeBehavior(VirtualStudentBehavior.WHISPERING);
        handRaised.changeBehavior(VirtualStudentBehavior.HAND_RAISED);
        calledOn.changeBehavior(VirtualStudentBehavior.CALLED_ON);
        answering.changeBehavior(VirtualStudentBehavior.ANSWERING);
        inDialogue.changeBehavior(VirtualStudentBehavior.DIALOGUE);

        List<HandRaise> handRaises = policy.select(
                List.of(focused, distracted, whispering, handRaised, calledOn,
                        answering, inDialogue),
                NOW, new ConstantRandom(0.9));

        assertThat(handRaises).extracting(HandRaise::studentId)
                .containsExactlyInAnyOrder(studentId(1), studentId(2));
    }

    @Test
    void scoreAboveThresholdUsesExactFormulaWeights() {
        // 1.0*0.3 + 0.0*0.2 + 0.0*0.2 + 1.0*0.2 + 0.0*0.1 = exactly 0.5, so it is not selected.
        VirtualStudentRuntime atThreshold = student(1, "1.00", "0.00", "0.00");
        // 0.5*0.3 + 0.5*0.2 + 0.5*0.2 + 1.0*0.2 + 1.0*0.1 = 0.65.
        VirtualStudentRuntime aboveThreshold = student(2, "0.50", "0.50", "0.50");

        List<HandRaise> handRaises = policy.select(
                List.of(atThreshold, aboveThreshold), NOW, new ScriptedRandom(0.0, 1.0));

        assertThat(handRaises).extracting(HandRaise::studentId)
                .containsExactly(studentId(2));
    }

    @Test
    void handRaiseEnergyAndCooldownLiveInDynamicsNotSelection() {
        VirtualStudentConfig config = config(1, "0.50", "0.50", "0.50");
        VirtualStudentDynamics dynamics = new VirtualStudentDynamics(new ConstantRandom(0.5));
        dynamics.initialize(List.of(config));
        VirtualStudentRuntime student = dynamics.find(studentId(1));

        List<HandRaise> handRaises =
                policy.select(List.of(student), NOW, new ConstantRandom(0.5));

        assertThat(handRaises).hasSize(1);
        assertThat(student.attention()).isCloseTo(0.5, within(1e-9));
        assertThat(student.handRaiseEnergy()).isEqualTo(1.0);
        assertThat(student.cooldownUntil()).isNull();

        dynamics.applyHandRaised(handRaises.get(0).studentId(), NOW,
                new ScriptedRandom(1.0, 0.0));

        assertThat(student.attention()).isCloseTo(0.54, within(1e-9));
        assertThat(student.handRaiseEnergy()).isCloseTo(0.84, within(1e-9));
        assertThat(student.cooldownUntil()).isEqualTo(NOW.plusSeconds(45));
    }

    @Test
    void returnsEmptyWhenNoCandidateCanBeSelected() {
        VirtualStudentRuntime whispering = student(1, "1.00", "1.00", "1.00");
        whispering.changeBehavior(VirtualStudentBehavior.WHISPERING);

        assertThat(policy.select(List.of(whispering), NOW, new ConstantRandom(0.9)))
                .isEmpty();
        assertThat(policy.select(List.of(), NOW, new ConstantRandom(0.9))).isEmpty();
    }

    private static VirtualStudentRuntime student(
            int seatNo, String activity, String questionRate, String attentionBaseline) {
        return new VirtualStudentRuntime(
                config(seatNo, activity, questionRate, attentionBaseline));
    }

    private static VirtualStudentConfig config(
            int seatNo, String activity, String questionRate, String attentionBaseline) {
        return new VirtualStudentConfig(
                studentId(seatNo),
                "学生" + seatNo,
                "avatar.png",
                "",
                bd(activity),
                bd(questionRate),
                bd("0.50"),
                bd(attentionBaseline),
                bd("0.00"),
                seatNo,
                seatNo);
    }

    private static UUID studentId(int seatNo) {
        return new UUID(0L, seatNo);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static final class ConstantRandom implements RandomGenerator {

        private final double value;

        private ConstantRandom(double value) {
            this.value = value;
        }

        @Override
        public long nextLong() {
            return 0L;
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }

    private static final class ScriptedRandom implements RandomGenerator {

        private final ArrayDeque<Double> values;

        private ScriptedRandom(double... values) {
            this.values = new ArrayDeque<>();
            for (double value : values) {
                this.values.add(value);
            }
        }

        @Override
        public long nextLong() {
            return 0L;
        }

        @Override
        public double nextDouble() {
            if (values.isEmpty()) {
                throw new AssertionError("unexpected random draw");
            }
            return values.removeFirst();
        }
    }
}
