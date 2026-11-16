package com.shijiangtai.m7teacher.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RubricTest {

    @Test
    void usesModeDefaultsWhenSnapshotIsEmpty() {
        Rubric rubric = Rubric.fromSnapshot("interview", Map.of());

        assertThat(rubric.dimensions()).containsExactly("content", "expression", "time");
        assertThat(rubric.weightOf("content")).isEqualTo(40);
    }

    @Test
    void rejectsWeightsThatDoNotSumToHundred() {
        assertThatThrownBy(() -> Rubric.fromSnapshot("interview",
                Map.of("content", 40, "expression", 30, "time", 20)))
                .isInstanceOf(M7Exception.class)
                .extracting("code")
                .isEqualTo(M7ErrorCode.DATA_INCONSISTENT);
    }

    @Test
    void rejectsUnknownDimensions() {
        assertThatThrownBy(() -> Rubric.fromSnapshot("interview",
                Map.of("content", 40, "expression", 30, "time", 30, "board", 0)))
                .isInstanceOf(M7Exception.class);
    }
}
