package com.shijiangtai.m7teacher.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalScoreCalculatorTest {

    private final FinalScoreCalculator calculator = new FinalScoreCalculator();
    private final Rubric rubric = Rubric.fromSnapshot("free", Map.of());

    @Test
    void composesSeventyThirtyTotalAndDimensions() {
        FinalScoreResult result = calculator.calculate(input(
                70, 30, ScoringMode.DIMENSION,
                ai("completed", "90.0", Map.of("content", "90.0")),
                teacher(ScoringMode.DIMENSION, "80.0", Map.of("content", "80.0"))));

        assertThat(result.status()).isEqualTo(FinalScoreStatus.COMPLETED);
        assertThat(result.finalTotal()).isEqualByComparingTo("87.0");
        assertThat(result.finalDimensions().get("content")).isEqualByComparingTo("87.0");
    }

    @Test
    void zeroTeacherWeightUsesAiOnlyAndIgnoresMissingTeacher() {
        FinalScoreResult result = calculator.calculate(input(
                100, 0, ScoringMode.DIMENSION,
                ai("completed", "90.0", Map.of("content", "90.0")),
                null));

        assertThat(result.status()).isEqualTo(FinalScoreStatus.COMPLETED);
        assertThat(result.finalTotal()).isEqualByComparingTo("90.0");
        assertThat(result.teacherTotal()).isNull();
        assertThat(result.finalDimensions().get("content")).isEqualByComparingTo("90.0");
    }

    @Test
    void zeroAiWeightIgnoresAiFailureAndUsesTeacherDimensions() {
        FinalScoreResult result = calculator.calculate(input(
                0, 100, ScoringMode.DIMENSION,
                ai("failed", null, Map.of()),
                teacher(ScoringMode.DIMENSION, "82.0", Map.of("content", "82.0"))));

        assertThat(result.status()).isEqualTo(FinalScoreStatus.COMPLETED);
        assertThat(result.finalTotal()).isEqualByComparingTo("82.0");
        assertThat(result.finalDimensions().get("content")).isEqualByComparingTo("82.0");
    }

    @Test
    void zeroAiWeightWithTotalModeTeacherHasNoDimensions() {
        FinalScoreResult result = calculator.calculate(input(
                0, 100, ScoringMode.TOTAL,
                ai("completed", "90.0", Map.of("content", "90.0")),
                teacher(ScoringMode.TOTAL, "80.0", Map.of())));

        assertThat(result.status()).isEqualTo(FinalScoreStatus.COMPLETED);
        assertThat(result.finalTotal()).isEqualByComparingTo("80.0");
        assertThat(result.finalDimensions()).isEmpty();
    }

    @Test
    void pendingAiWinsOverPendingTeacher() {
        FinalScoreResult result = calculator.calculate(input(
                70, 30, ScoringMode.DIMENSION,
                ai("pending", null, Map.of()),
                null));

        assertThat(result.status()).isEqualTo(FinalScoreStatus.PENDING_AI);
        assertThat(result.finalTotal()).isNull();
    }

    @Test
    void partialAndFailedMapToTheirBuckets() {
        assertThat(calculator.calculate(input(70, 30, ScoringMode.DIMENSION,
                ai("partial", null, Map.of()), null)).status())
                .isEqualTo(FinalScoreStatus.AI_PARTIAL);
        assertThat(calculator.calculate(input(70, 30, ScoringMode.DIMENSION,
                ai("failed", null, Map.of()), null)).status())
                .isEqualTo(FinalScoreStatus.AI_FAILED);
    }

    @Test
    void failedAiRetainsTeacherScoreButNoFinal() {
        FinalScoreResult result = calculator.calculate(input(
                70, 30, ScoringMode.DIMENSION,
                ai("failed", null, Map.of()),
                teacher(ScoringMode.DIMENSION, "80.0", Map.of("content", "80.0"))));

        assertThat(result.status()).isEqualTo(FinalScoreStatus.AI_FAILED);
        assertThat(result.teacherTotal()).isEqualByComparingTo("80.0");
        assertThat(result.finalTotal()).isNull();
    }

    @Test
    void completedAiWithoutTeacherIsPendingTeacher() {
        FinalScoreResult result = calculator.calculate(input(
                70, 30, ScoringMode.DIMENSION,
                ai("completed", "90.0", Map.of()),
                null));

        assertThat(result.status()).isEqualTo(FinalScoreStatus.PENDING_TEACHER);
        assertThat(result.aiTotal()).isEqualByComparingTo("90.0");
    }

    @Test
    void missingAiRowIsPendingAiWithDataInconsistency() {
        FinalScoreResult result = calculator.calculate(input(
                70, 30, ScoringMode.DIMENSION, null, null));

        assertThat(result.status()).isEqualTo(FinalScoreStatus.PENDING_AI);
        assertThat(result.errorCode()).isEqualTo("DATA_INCONSISTENT");
    }

    @Test
    void totalModeUsesAiDimensions() {
        FinalScoreResult result = calculator.calculate(input(
                70, 30, ScoringMode.TOTAL,
                ai("completed", "90.0", Map.of("content", "90.0")),
                teacher(ScoringMode.TOTAL, "80.0", Map.of())));

        assertThat(result.status()).isEqualTo(FinalScoreStatus.COMPLETED);
        assertThat(result.finalTotal()).isEqualByComparingTo("87.0");
        assertThat(result.finalDimensions().get("content")).isEqualByComparingTo("90.0");
    }

    @Test
    void roundsHalfUpToOneDecimal() {
        FinalScoreResult result = calculator.calculate(input(
                70, 30, ScoringMode.DIMENSION,
                ai("completed", "85.55", Map.of()),
                teacher(ScoringMode.DIMENSION, "82.32", Map.of())));

        assertThat(result.finalTotal()).isEqualByComparingTo("84.6");
    }

    private FinalScoreInput input(
            int aiWeight,
            int teacherWeight,
            ScoringMode scoringMode,
            AiEvaluationSnapshot ai,
            TeacherScore teacher) {
        return new FinalScoreInput(aiWeight, teacherWeight, scoringMode, rubric, ai, teacher,
                null, null);
    }

    private static AiEvaluationSnapshot ai(
            String status, String total, Map<String, String> dimensions) {
        LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
        dimensions.forEach((key, value) -> scores.put(key, new BigDecimal(value)));
        return new AiEvaluationSnapshot(UUID.randomUUID(), status,
                total == null ? null : new BigDecimal(total), scores, null, null);
    }

    private static TeacherScore teacher(
            ScoringMode mode, String total, Map<String, String> dimensions) {
        LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
        dimensions.forEach((key, value) -> scores.put(key, new BigDecimal(value)));
        return new TeacherScore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, mode, scores,
                new BigDecimal(total), null, 1, UUID.randomUUID(), Instant.now(),
                Instant.now());
    }
}
