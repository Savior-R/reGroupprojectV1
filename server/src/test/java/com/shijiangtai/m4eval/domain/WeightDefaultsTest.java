package com.shijiangtai.m4eval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Snapshot weights must stay applicable and sum to 100 (06 §3.2). */
class WeightDefaultsTest {

    @Test
    void freeDefaultsSumToOneHundred() {
        Map<String, Integer> weights = WeightDefaults.forMode(EvaluationMode.FREE);

        assertThat(weights).containsKeys("content", "expression", "interaction", "board", "time");
        assertThat(weights.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    void interviewDefaultsUseThreeDimensions() {
        Map<String, Integer> weights = WeightDefaults.forMode(EvaluationMode.INTERVIEW);

        assertThat(weights).containsOnlyKeys("content", "expression", "time");
        assertThat(weights.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    void emptySnapshotFallsBackToDefaults() {
        assertThat(WeightDefaults.normalize(EvaluationMode.SCENARIO, Map.of()))
                .isEqualTo(WeightDefaults.forMode(EvaluationMode.SCENARIO));
    }

    @Test
    void dropsDimensionsThatAreNotApplicableToTheMode() {
        Map<String, Integer> snapshot = Map.of(
                "content", 40,
                "expression", 30,
                "time", 30,
                "interaction", 20);

        Map<String, Integer> normalized = WeightDefaults.normalize(
                EvaluationMode.INTERVIEW, snapshot);

        assertThat(normalized).containsOnlyKeys("content", "expression", "time");
        assertThat(normalized.get("content")).isEqualTo(40);
        assertThat(normalized.get("expression")).isEqualTo(30);
        assertThat(normalized.get("time")).isEqualTo(30);
    }

    @Test
    void rejectsSnapshotWhoseApplicableWeightsDoNotSumToHundred() {
        assertThatThrownBy(() -> WeightDefaults.normalize(
                        EvaluationMode.INTERVIEW,
                        Map.of("content", 40, "expression", 30, "time", 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to 100");
    }

    @Test
    void rejectsNegativeWeight() {
        assertThatThrownBy(() -> WeightDefaults.normalize(
                        EvaluationMode.INTERVIEW,
                        Map.of("content", 80, "expression", 30, "time", -10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }
}
