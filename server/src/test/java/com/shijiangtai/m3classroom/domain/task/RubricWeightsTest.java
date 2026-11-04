package com.shijiangtai.m3classroom.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RubricWeightsTest {

    @Test
    void freeDefaultWeightsSumTo100() {
        assertThat(RubricWeights.defaults(TaskMode.FREE).sum()).isEqualTo(100);
    }

    @Test
    void scenarioDefaultWeightsSumTo100() {
        assertThat(RubricWeights.defaults(TaskMode.SCENARIO).sum()).isEqualTo(100);
    }

    @Test
    void interviewDefaultWeightsSumTo100() {
        assertThat(RubricWeights.defaults(TaskMode.INTERVIEW).sum()).isEqualTo(100);
    }

    @Test
    void interviewWeightsRejectFreeDimension() {
        assertThatThrownBy(() -> RubricWeights.of(TaskMode.INTERVIEW,
                Map.of("content", 40, "expression", 30, "board", 30)))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TASK_CONFIG_INVALID));
    }

    @Test
    void weightsRejectValueAbove100() {
        assertThatThrownBy(() -> new RubricWeights(Map.of(
                "content", 101, "expression", 0, "interaction", 0, "board", 0, "time", 0)))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TASK_CONFIG_INVALID));
    }

    @Test
    void ofRequiresExactModeKeys() {
        assertThatThrownBy(() -> RubricWeights.of(TaskMode.FREE,
                Map.of("content", 100)))
                .isInstanceOf(DomainException.class);
    }
}
