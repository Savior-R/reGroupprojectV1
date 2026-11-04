package com.shijiangtai.m3classroom.domain.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DisciplineAggregatorTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final DisciplineAggregator aggregator = new DisciplineAggregator();

    @Test
    void levelZeroWhenQuiet() {
        List<VirtualStudentRuntime> students = noisyStudents(20, 0, 0);

        DisciplineSnapshot snapshot = aggregator.aggregate(students, 0);

        assertThat(snapshot.level()).isZero();
        assertThat(snapshot.distractedCount()).isZero();
        assertThat(snapshot.whisperingCount()).isZero();
        assertThat(snapshot.noisyRatio()).isZero();
        assertThat(snapshot.avgAttention()).isCloseTo(0.5, within(1e-9));
    }

    @ParameterizedTest(name = "total={0}, distracted={1}, whispering={2} -> level {3}")
    @MethodSource("levelCases")
    void levelUsesMaxOfCountAndRatio(
            int total, int distracted, int whispering, int expectedLevel) {
        List<VirtualStudentRuntime> students =
                noisyStudents(total, distracted, whispering);

        DisciplineSnapshot snapshot = aggregator.aggregate(students, 0);

        assertThat(snapshot.level()).isEqualTo(expectedLevel);
        assertThat(snapshot.distractedCount()).isEqualTo(distracted);
        assertThat(snapshot.whisperingCount()).isEqualTo(whispering);
        assertThat(snapshot.noisyRatio())
                .isCloseTo((double) (distracted + whispering) / total, within(1e-9));
    }

    static Stream<Arguments> levelCases() {
        return Stream.of(
                Arguments.of(20, 1, 0, 0),
                Arguments.of(20, 0, 1, 1),
                Arguments.of(20, 2, 0, 1),
                Arguments.of(20, 3, 0, 2),
                Arguments.of(20, 4, 1, 2),
                Arguments.of(20, 6, 0, 3),
                Arguments.of(10, 1, 0, 1),
                Arguments.of(5, 1, 0, 1),
                Arguments.of(5, 2, 0, 2),
                Arguments.of(10, 5, 0, 2),
                Arguments.of(10, 6, 0, 3));
    }

    @Test
    void levelTwoPersistingOver20sBecomesLevelThree() {
        List<VirtualStudentRuntime> levelTwo = noisyStudents(20, 3, 0);

        assertThat(aggregator.aggregate(levelTwo, 0).level()).isEqualTo(2);
        assertThat(aggregator.aggregate(levelTwo, 20_000).level()).isEqualTo(2);
        assertThat(aggregator.aggregate(levelTwo, 20_001).level()).isEqualTo(3);

        List<VirtualStudentRuntime> levelOne = noisyStudents(20, 2, 0);
        assertThat(aggregator.aggregate(levelOne, 60_000).level()).isEqualTo(1);
    }

    @Test
    void levelTwoDurationEscalationEmitsLevelThreeChange() {
        VirtualStudentDynamics dynamics = dynamics();
        for (int seatNo : List.of(1, 2, 3)) {
            dynamics.find(studentId(seatNo))
                    .changeBehavior(VirtualStudentBehavior.DISTRACTED);
        }

        List<ClassroomEvent> entered = dynamics.disciplineLevelChangedIfNeeded(NOW);

        assertThat(types(entered)).containsExactly("classroom.discipline_level_changed");
        assertThat(entered.get(0).payload())
                .containsEntry("from", 0)
                .containsEntry("to", 2);

        List<ClassroomEvent> beforeThreshold =
                dynamics.disciplineLevelChangedIfNeeded(NOW.plusSeconds(20));
        assertThat(beforeThreshold).isEmpty();

        List<ClassroomEvent> escalated =
                dynamics.disciplineLevelChangedIfNeeded(NOW.plusSeconds(21));

        assertThat(types(escalated)).containsExactly("classroom.discipline_level_changed");
        assertThat(escalated.get(0).payload())
                .containsEntry("from", 2)
                .containsEntry("to", 3)
                .containsEntry("duration_ms", 21_000L)
                .containsEntry("reason", "level_duration")
                .hasSize(8);
    }

    @Test
    void disciplineLevelDoesNotFlapAfterDurationEscalation() {
        VirtualStudentDynamics dynamics = dynamics();
        for (int seatNo : List.of(1, 2, 3)) {
            dynamics.find(studentId(seatNo))
                    .changeBehavior(VirtualStudentBehavior.DISTRACTED);
        }

        List<ClassroomEvent> entered = dynamics.disciplineLevelChangedIfNeeded(NOW);
        assertThat(entered.get(0).payload())
                .containsEntry("from", 0)
                .containsEntry("to", 2);

        List<ClassroomEvent> escalated =
                dynamics.disciplineLevelChangedIfNeeded(NOW.plusSeconds(21));
        assertThat(escalated.get(0).payload())
                .containsEntry("from", 2)
                .containsEntry("to", 3)
                .containsEntry("duration_ms", 21_000L)
                .containsEntry("reason", "level_duration");

        // The raw-duration clock must not be reset by the 2 -> 3 escalation.
        assertThat(dynamics.disciplineLevelChangedIfNeeded(NOW.plusSeconds(21))).isEmpty();
        assertThat(dynamics.disciplineLevelChangedIfNeeded(NOW.plusSeconds(22))).isEmpty();
        assertThat(dynamics.disciplineLevelChangedIfNeeded(NOW.plusSeconds(41))).isEmpty();

        dynamics.find(studentId(3)).changeBehavior(VirtualStudentBehavior.FOCUSED);
        List<ClassroomEvent> dropped =
                dynamics.disciplineLevelChangedIfNeeded(NOW.plusSeconds(42));

        assertThat(dropped).hasSize(1);
        assertThat(dropped.get(0).payload())
                .containsEntry("from", 3)
                .containsEntry("to", 1);
    }

    @Test
    void disciplineEventsAreRecordOnly() {
        List<VirtualStudentRuntime> students = noisyStudents(20, 3, 0);
        List<Double> attentionBefore = students.stream()
                .map(VirtualStudentRuntime::attention)
                .toList();
        List<VirtualStudentBehavior> behaviorBefore = students.stream()
                .map(VirtualStudentRuntime::behavior)
                .toList();

        DisciplineSnapshot snapshot = aggregator.aggregate(students, 0);

        assertThat(snapshot.level()).isEqualTo(2);
        assertThat(students.stream().map(VirtualStudentRuntime::attention).toList())
                .isEqualTo(attentionBefore);
        assertThat(students.stream().map(VirtualStudentRuntime::behavior).toList())
                .isEqualTo(behaviorBefore);

        VirtualStudentDynamics dynamics = dynamics();
        for (int seatNo : List.of(1, 5, 9)) {
            dynamics.find(studentId(seatNo))
                    .changeBehavior(VirtualStudentBehavior.DISTRACTED);
        }

        List<ClassroomEvent> events = dynamics.disciplineLevelChangedIfNeeded(NOW);

        assertThat(types(events)).containsExactly("classroom.discipline_level_changed");
        assertThat(events.get(0).payload())
                .containsEntry("from", 0)
                .containsEntry("to", 2)
                .containsEntry("distracted_count", 3)
                .containsEntry("whispering_count", 0)
                .containsEntry("noisy_ratio", 3.0 / 20.0)
                .containsEntry("avg_attention", 0.5)
                .containsEntry("duration_ms", 0L)
                .containsEntry("reason", "aggregate")
                .hasSize(8);

        assertThat(dynamics.students().stream().map(VirtualStudentRuntime::attention).toList())
                .containsOnly(0.5);
        assertThat(dynamics.students().stream()
                .filter(student -> student.behavior() == VirtualStudentBehavior.DISTRACTED)
                .count())
                .isEqualTo(3);
        assertThat(dynamics.disciplineLevelChangedIfNeeded(NOW.plusSeconds(1))).isEmpty();
    }

    private static VirtualStudentDynamics dynamics() {
        List<VirtualStudentConfig> configs = new ArrayList<>();
        for (int seatNo = 1; seatNo <= 20; seatNo++) {
            configs.add(config(seatNo));
        }
        VirtualStudentDynamics dynamics = new VirtualStudentDynamics(new ConstantRandom());
        dynamics.initialize(configs);
        return dynamics;
    }

    private static List<VirtualStudentRuntime> noisyStudents(
            int total, int distracted, int whispering) {
        List<VirtualStudentRuntime> students = new ArrayList<>();
        for (int seatNo = 1; seatNo <= total; seatNo++) {
            students.add(new VirtualStudentRuntime(config(seatNo)));
        }
        for (int index = 0; index < distracted; index++) {
            students.get(index).changeBehavior(VirtualStudentBehavior.DISTRACTED);
        }
        for (int index = distracted; index < distracted + whispering; index++) {
            students.get(index).changeBehavior(VirtualStudentBehavior.WHISPERING);
        }
        return students;
    }

    private static VirtualStudentConfig config(int seatNo) {
        return new VirtualStudentConfig(
                studentId(seatNo),
                "学生" + seatNo,
                "avatar.png",
                "",
                bd("0.50"),
                bd("0.50"),
                bd("0.50"),
                bd("0.50"),
                bd("0.50"),
                seatNo,
                seatNo);
    }

    private static UUID studentId(int seatNo) {
        return new UUID(0L, seatNo);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static List<String> types(List<ClassroomEvent> events) {
        return events.stream().map(ClassroomEvent::type).toList();
    }

    private static final class ConstantRandom
            implements java.util.random.RandomGenerator {

        @Override
        public long nextLong() {
            return 0L;
        }

        @Override
        public double nextDouble() {
            return 0.5;
        }
    }
}
