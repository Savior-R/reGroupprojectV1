package com.shijiangtai.m4eval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M4-T04 / M4-T05: weighted totals, partial semantics and zero-weight warnings. */
class EvaluationPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static final Map<String, Integer> FREE_WEIGHTS = Map.of(
            "content", 30, "expression", 20, "interaction", 20, "board", 15, "time", 15);

    @Test
    void composesWeightedTotalToOneDecimal() {
        Map<String, BigDecimal> scores = Map.of(
                "content", new BigDecimal("82.5"),
                "expression", new BigDecimal("80.0"),
                "interaction", new BigDecimal("76.5"),
                "board", new BigDecimal("88.0"),
                "time", new BigDecimal("90.0"));

        BigDecimal total = EvaluationPolicy.totalScore(FREE_WEIGHTS, scores);

        // 24.75 + 16.0 + 15.3 + 13.2 + 13.5 = 82.75 -> HALF_UP 82.8
        assertThat(total).isEqualByComparingTo("82.8");
        assertThat(total.scale()).isEqualTo(1);
    }

    @Test
    void rejectsTotalWhenRequiredDimensionIsMissing() {
        Map<String, BigDecimal> scores = Map.of("content", new BigDecimal("82.5"));

        assertThatThrownBy(() -> EvaluationPolicy.totalScore(FREE_WEIGHTS, scores))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required dimension");
    }

    @Test
    void completedWhenEveryRequiredDimensionIsPresent() {
        EvaluationOutcome outcome = EvaluationPolicy.decide(
                EvaluationMode.FREE,
                FREE_WEIGHTS,
                allDimensions(),
                List.of("REFERENCE_MISSING"),
                List.of(),
                "mock",
                "m4-eval-v1",
                DimensionCatalog.RUBRIC_VERSION,
                Map.of(),
                NOW);

        assertThat(outcome.status()).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(outcome.totalScore()).isEqualByComparingTo("82.8");
        assertThat(outcome.warnings()).contains("REFERENCE_MISSING");
        assertThat(outcome.partialReasons()).isEmpty();
    }

    @Test
    void missingRequiredDimensionProducesPartialWithoutTotal() {
        Map<String, BigDecimal> scores = new LinkedHashMap<>(allDimensions());
        scores.remove("expression");

        EvaluationOutcome outcome = EvaluationPolicy.decide(
                EvaluationMode.FREE, FREE_WEIGHTS, scores, List.of(), List.of(),
                "mock", "m4-eval-v1", DimensionCatalog.RUBRIC_VERSION, Map.of(), NOW);

        assertThat(outcome.status()).isEqualTo(EvaluationStatus.PARTIAL);
        assertThat(outcome.totalScore()).isNull();
        assertThat(outcome.partialReasons()).contains("DIMENSION_FAILED");
    }

    @Test
    void missingZeroWeightDimensionIsOnlyAWarning() {
        Map<String, Integer> weights = Map.of(
                "content", 30, "expression", 20, "interaction", 20, "board", 0, "time", 30);
        Map<String, BigDecimal> scores = new LinkedHashMap<>(allDimensions());
        scores.remove("board");

        EvaluationOutcome outcome = EvaluationPolicy.decide(
                EvaluationMode.FREE, weights, scores, List.of(), List.of(),
                "mock", "m4-eval-v1", DimensionCatalog.RUBRIC_VERSION, Map.of(), NOW);

        assertThat(outcome.status()).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(outcome.totalScore()).isNotNull();
        assertThat(outcome.warnings()).contains(EvaluationPolicy.ZERO_WEIGHT_MISSING);
    }

    @Test
    void fatalPartialReasonProducesPartialEvenWithAllDimensions() {
        EvaluationOutcome outcome = EvaluationPolicy.decide(
                EvaluationMode.FREE,
                FREE_WEIGHTS,
                allDimensions(),
                List.of(),
                List.of("AUDIO_MISSING"),
                "mock",
                "m4-eval-v1",
                DimensionCatalog.RUBRIC_VERSION,
                Map.of(),
                NOW);

        assertThat(outcome.status()).isEqualTo(EvaluationStatus.PARTIAL);
        assertThat(outcome.totalScore()).isNull();
        assertThat(outcome.partialReasons()).contains("AUDIO_MISSING");
    }

    @Test
    void noDimensionAtAllFails() {
        EvaluationOutcome outcome = EvaluationPolicy.decide(
                EvaluationMode.INTERVIEW,
                WeightDefaults.forMode(EvaluationMode.INTERVIEW),
                Map.of(),
                List.of(),
                List.of(),
                "mock",
                "m4-eval-v1",
                DimensionCatalog.RUBRIC_VERSION,
                Map.of(),
                NOW);

        assertThat(outcome.status()).isEqualTo(EvaluationStatus.FAILED);
        assertThat(outcome.totalScore()).isNull();
    }

    @Test
    void deadLetterKeepsFinishedDimensionsAsPartial() {
        EvaluationOutcome partial = EvaluationPolicy.deadLetter(
                Map.of("content", new BigDecimal("80.0")), "worker crashed");
        EvaluationOutcome failed = EvaluationPolicy.deadLetter(Map.of(), "worker crashed");

        assertThat(partial.status()).isEqualTo(EvaluationStatus.PARTIAL);
        assertThat(partial.totalScore()).isNull();
        assertThat(partial.partialReasons()).contains("DIMENSION_TIMEOUT");
        assertThat(failed.status()).isEqualTo(EvaluationStatus.FAILED);
    }

    private static Map<String, BigDecimal> allDimensions() {
        return Map.of(
                "content", new BigDecimal("82.5"),
                "expression", new BigDecimal("80.0"),
                "interaction", new BigDecimal("76.5"),
                "board", new BigDecimal("88.0"),
                "time", new BigDecimal("90.0"));
    }
}
