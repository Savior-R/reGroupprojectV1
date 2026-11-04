package com.shijiangtai.m3classroom.domain.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.shared.domain.DomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class VirtualStudentDynamicsTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Test
    void attentionDecaysByBaselineFormula() {
        VirtualStudentDynamics dynamics = dynamics(
                config(1, "0.50", "0.0"), config(2, "1.00", "0.0"));

        dynamics.tick(1, NOW, new ConstantRandom(0.5));

        assertThat(dynamics.find(studentId(1)).attention())
                .isCloseTo(0.496, within(1e-9));
        assertThat(dynamics.find(studentId(2)).attention())
                .isCloseTo(0.998, within(1e-9));

        dynamics.tick(10, NOW.plusSeconds(11), new ConstantRandom(0.5));

        assertThat(dynamics.find(studentId(1)).attention())
                .isCloseTo(0.456, within(1e-9));
        assertThat(dynamics.find(studentId(2)).attention())
                .isCloseTo(0.978, within(1e-9));
    }

    @Test
    void attentionClampsToFloorAndCeiling() {
        VirtualStudentDynamics dynamics = dynamics(config(1, "0.50", "0.0"));
        VirtualStudentRuntime student = dynamics.find(studentId(1));

        dynamics.tick(10_000, NOW, new ConstantRandom(0.5));

        assertThat(student.attention()).isEqualTo(0.05);

        student.applyAttentionDelta(5.0);
        assertThat(student.attention()).isEqualTo(1.0);

        student.applyAttentionDelta(-5.0);
        assertThat(student.attention()).isEqualTo(0.05);

        student.applyAttentionDelta(-0.01);
        assertThat(student.attention()).isEqualTo(0.05);
    }

    @Test
    void handRaiseEnergyRecoversAndClamps() {
        VirtualStudentDynamics dynamics = dynamics(config(1, "0.50", "0.0"));
        VirtualStudentRuntime student = dynamics.find(studentId(1));

        assertThat(student.handRaiseEnergy()).isEqualTo(1.0);

        student.markHandRaised(NOW);

        assertThat(student.handRaiseEnergy()).isCloseTo(0.8, within(1e-9));
        assertThat(student.cooldownUntil()).isEqualTo(NOW.plusSeconds(45));
        assertThat(student.isCoolingDown(NOW)).isTrue();
        assertThat(student.isCoolingDown(NOW.plusSeconds(44))).isTrue();
        assertThat(student.isCoolingDown(NOW.plusSeconds(45))).isFalse();

        dynamics.tick(10, NOW.plusSeconds(46), new ConstantRandom(0.5));
        assertThat(student.handRaiseEnergy()).isCloseTo(0.85, within(1e-9));

        dynamics.tick(100, NOW.plusSeconds(146), new ConstantRandom(0.5));
        assertThat(student.handRaiseEnergy()).isEqualTo(1.0);
    }

    @Test
    void lowAttentionCanTriggerDistractedOrWhispering() {
        VirtualStudentDynamics dynamics = dynamics(
                config(1, "0.10", "1.0"), config(2, "0.50", "0.0"));

        List<ClassroomEvent> events =
                dynamics.tick(1, NOW, new ScriptedRandom(0.0, 0.8, 0.0));

        VirtualStudentRuntime student = dynamics.find(studentId(1));
        assertThat(student.behavior()).isEqualTo(VirtualStudentBehavior.WHISPERING);
        assertThat(events.stream()
                .filter(candidate -> candidate.type().equals("student.state_changed"))
                .toList())
                .hasSize(1);
        assertThat(event(events, "student.state_changed").payload())
                .containsEntry("student_id", studentId(1))
                .containsEntry("from", "focused")
                .containsEntry("to", "whispering")
                .containsEntry("reason", "low_attention")
                .hasSize(4);
    }

    @Test
    void highAttentionDoesNotTriggerDiscipline() {
        VirtualStudentDynamics dynamics = dynamics(config(1, "0.50", "1.0"));

        List<ClassroomEvent> events = dynamics.tick(1, NOW, new ConstantRandom(0.0));

        assertThat(dynamics.find(studentId(1)).behavior())
                .isEqualTo(VirtualStudentBehavior.FOCUSED);
        assertThat(events).isEmpty();
    }

    @Test
    void manageStudentRaisesAttentionBy025() {
        VirtualStudentDynamics dynamics = dynamics(config(1, "0.50", "0.0"));
        VirtualStudentRuntime student = dynamics.find(studentId(1));
        student.applyAttentionDelta(-0.4);

        dynamics.applyManaged(studentId(1));

        assertThat(student.attention()).isCloseTo(0.35, within(1e-9));

        student.applyAttentionDelta(0.5);
        dynamics.applyManaged(studentId(1));

        assertThat(student.attention()).isEqualTo(1.0);
    }

    @Test
    void eventMatrixAppliesExpectedDeltas() {
        VirtualStudentDynamics dynamics = dynamics(
                config(1, "0.50", "0.0"), config(2, "0.50", "0.0"));
        VirtualStudentRuntime first = dynamics.find(studentId(1));
        VirtualStudentRuntime second = dynamics.find(studentId(2));

        dynamics.applyQuestionAsked();
        assertThat(first.attention()).isCloseTo(0.55, within(1e-9));
        assertThat(second.attention()).isCloseTo(0.55, within(1e-9));

        dynamics.applyCalledOn(studentId(1));
        assertThat(first.attention()).isCloseTo(0.70, within(1e-9));
        assertThat(first.handRaiseEnergy()).isEqualTo(1.0);
        assertThat(second.attention()).isCloseTo(0.53, within(1e-9));

        first.applyEnergyDelta(-0.5);
        dynamics.applyMarked(studentId(1), Correctness.CORRECT);
        assertThat(first.attention()).isCloseTo(0.80, within(1e-9));
        assertThat(first.handRaiseEnergy()).isCloseTo(0.55, within(1e-9));

        dynamics.applyMarked(studentId(1), Correctness.PARTIAL);
        assertThat(first.attention()).isCloseTo(0.82, within(1e-9));

        dynamics.applyMarked(studentId(1), Correctness.WRONG);
        assertThat(first.attention()).isCloseTo(0.72, within(1e-9));
    }

    @Test
    void handRaiseAnswerFollowUpAndClassWideEventsApplyExpectedDeltas() {
        VirtualStudentDynamics dynamics = dynamics(
                config(1, "0.50", "0.0"), config(2, "0.50", "0.0"));
        VirtualStudentRuntime first = dynamics.find(studentId(1));
        VirtualStudentRuntime second = dynamics.find(studentId(2));

        dynamics.applyHandRaised(studentId(1));
        dynamics.applyAnswer(studentId(1));
        dynamics.applyFollowUp(studentId(1));
        dynamics.applyQaStarted();
        dynamics.applyHandWindowTimeout();

        assertThat(first.attention()).isCloseTo(0.71, within(1e-9));
        assertThat(second.attention()).isCloseTo(0.51, within(1e-9));
    }

    @Test
    void stateAndDisciplineEventsApplyDeltasToStudentAndNeighbours() {
        VirtualStudentDynamics dynamics = dynamics(
                config(1, "0.50", "0.0"),
                config(2, "0.50", "0.0"),
                config(3, "0.50", "0.0"));
        VirtualStudentRuntime first = dynamics.find(studentId(1));
        VirtualStudentRuntime second = dynamics.find(studentId(2));
        VirtualStudentRuntime third = dynamics.find(studentId(3));
        for (VirtualStudentRuntime student : dynamics.students()) {
            student.applyAttentionDelta(0.1);
            student.applyEnergyDelta(-0.5);
        }

        dynamics.applyStateChanged(studentId(2), VirtualStudentBehavior.DISTRACTED);

        assertThat(second.behavior()).isEqualTo(VirtualStudentBehavior.DISTRACTED);
        assertThat(second.attention()).isCloseTo(0.55, within(1e-9));
        assertThat(second.handRaiseEnergy()).isCloseTo(0.45, within(1e-9));
        assertThat(first.attention()).isCloseTo(0.58, within(1e-9));
        assertThat(third.attention()).isCloseTo(0.58, within(1e-9));

        second.applyAttentionDelta(0.05);
        first.applyAttentionDelta(0.02);
        third.applyAttentionDelta(0.02);
        dynamics.applyStateChanged(studentId(2), VirtualStudentBehavior.WHISPERING);

        assertThat(second.behavior()).isEqualTo(VirtualStudentBehavior.WHISPERING);
        assertThat(second.attention()).isCloseTo(0.52, within(1e-9));
        assertThat(second.handRaiseEnergy()).isCloseTo(0.40, within(1e-9));
        assertThat(first.attention()).isCloseTo(0.57, within(1e-9));
        assertThat(third.attention()).isCloseTo(0.57, within(1e-9));

        second.applyAttentionDelta(0.08);
        first.applyAttentionDelta(0.03);
        third.applyAttentionDelta(0.03);
        dynamics.applyDisciplineEvent(studentId(2));

        assertThat(second.attention()).isCloseTo(0.50, within(1e-9));
        assertThat(first.attention()).isCloseTo(0.57, within(1e-9));
        assertThat(third.attention()).isCloseTo(0.57, within(1e-9));

        dynamics.applyStateChanged(studentId(2), VirtualStudentBehavior.FOCUSED);

        assertThat(second.behavior()).isEqualTo(VirtualStudentBehavior.FOCUSED);
        assertThat(second.attention()).isCloseTo(0.50, within(1e-9));
    }

    @Test
    void eventDeltaNoiseIsTwentyPercentWithMinimumFiveThousandths() {
        VirtualStudentDynamics highNoise = dynamicsWithRandom(1.0, config(1, "0.50", "0.0"));
        highNoise.applyQuestionAsked();
        assertThat(highNoise.find(studentId(1)).attention())
                .isCloseTo(0.56, within(1e-9));

        VirtualStudentDynamics lowNoise = dynamicsWithRandom(0.0, config(1, "0.50", "0.0"));
        lowNoise.applyQuestionAsked();
        assertThat(lowNoise.find(studentId(1)).attention())
                .isCloseTo(0.54, within(1e-9));

        VirtualStudentDynamics highMinimum = dynamicsWithRandom(1.0, config(1, "0.50", "0.0"));
        highMinimum.applyMarked(studentId(1), Correctness.PARTIAL);
        assertThat(highMinimum.find(studentId(1)).attention())
                .isCloseTo(0.525, within(1e-9));

        VirtualStudentDynamics lowMinimum = dynamicsWithRandom(0.0, config(1, "0.50", "0.0"));
        lowMinimum.applyMarked(studentId(1), Correctness.PARTIAL);
        assertThat(lowMinimum.find(studentId(1)).attention())
                .isCloseTo(0.515, within(1e-9));
    }

    @Test
    void tickEmitsStudentStateChangedOnlyOnTransition() {
        VirtualStudentDynamics dynamics = dynamics(
                config(1, "0.10", "1.0"), config(2, "0.50", "0.0"));
        VirtualStudentRuntime noisy = dynamics.find(studentId(1));

        List<ClassroomEvent> triggered =
                dynamics.tick(1, NOW, new ScriptedRandom(0.0, 0.8, 0.0));

        assertThat(noisy.behavior()).isEqualTo(VirtualStudentBehavior.WHISPERING);
        assertThat(studentStateChanges(triggered)).hasSize(1);
        assertThat(event(triggered, "student.state_changed").payload())
                .containsEntry("student_id", studentId(1))
                .containsEntry("from", "focused")
                .containsEntry("to", "whispering")
                .containsEntry("reason", "low_attention");

        List<ClassroomEvent> steady = dynamics.tick(1, NOW.plusSeconds(1), new ScriptedRandom());
        assertThat(steady).isEmpty();

        List<ClassroomEvent> expired =
                dynamics.tick(10, NOW.plusSeconds(11), new ScriptedRandom());

        assertThat(noisy.behavior()).isEqualTo(VirtualStudentBehavior.FOCUSED);
        assertThat(studentStateChanges(expired)).hasSize(1);
        assertThat(event(expired, "student.state_changed").payload())
                .containsEntry("student_id", studentId(1))
                .containsEntry("from", "whispering")
                .containsEntry("to", "focused")
                .containsEntry("reason", "state_duration_elapsed");
    }

    @Test
    void unknownStudentIsRejected() {
        VirtualStudentDynamics dynamics = dynamics(config(1, "0.50", "0.0"));

        assertThatThrownBy(() -> dynamics.applyManaged(studentId(99)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void negativeElapsedSecondsIsRejected() {
        VirtualStudentDynamics dynamics = dynamics(config(1, "0.50", "0.0"));

        assertThatThrownBy(() -> dynamics.tick(-1, NOW, new ConstantRandom(0.5)))
                .isInstanceOf(DomainException.class);
    }

    private static VirtualStudentDynamics dynamics(VirtualStudentConfig... configs) {
        return dynamicsWithRandom(0.5, configs);
    }

    private static VirtualStudentDynamics dynamicsWithRandom(
            double fixedRandom, VirtualStudentConfig... configs) {
        VirtualStudentDynamics dynamics =
                new VirtualStudentDynamics(new ConstantRandom(fixedRandom));
        dynamics.initialize(List.of(configs));
        return dynamics;
    }

    private static VirtualStudentConfig config(
            int seatNo, String attentionBaseline, String disciplineProbability) {
        return new VirtualStudentConfig(
                studentId(seatNo),
                "学生" + seatNo,
                "avatar.png",
                "",
                bd("0.50"),
                bd("0.50"),
                bd("0.50"),
                bd(attentionBaseline),
                bd(disciplineProbability),
                seatNo,
                seatNo);
    }

    private static UUID studentId(int seatNo) {
        return new UUID(0L, seatNo);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static List<ClassroomEvent> studentStateChanges(List<ClassroomEvent> events) {
        return events.stream()
                .filter(event -> event.type().equals("student.state_changed"))
                .toList();
    }

    private static ClassroomEvent event(List<ClassroomEvent> events, String type) {
        return events.stream()
                .filter(candidate -> candidate.type().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing event " + type));
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
            throw new AssertionError("nextLong is not expected");
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
