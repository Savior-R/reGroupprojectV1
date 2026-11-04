package com.shijiangtai.m3classroom.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.DomainException;
import org.junit.jupiter.api.Test;

class ScenarioConfigTest {

    @Test
    void nullValuesAreAllowed() {
        ScenarioConfig scenario = new ScenarioConfig(null, null);
        assertThat(scenario.role()).isNull();
        assertThat(scenario.situation()).isNull();
    }

    @Test
    void blankValuesAreNormalizedToNull() {
        ScenarioConfig scenario = new ScenarioConfig("  ", " ");
        assertThat(scenario.role()).isNull();
        assertThat(scenario.situation()).isNull();
    }

    @Test
    void valuesAreTrimmed() {
        ScenarioConfig scenario = new ScenarioConfig(" 班主任 ", " 学生玩手机 ");
        assertThat(scenario.role()).isEqualTo("班主任");
        assertThat(scenario.situation()).isEqualTo("学生玩手机");
    }

    @Test
    void rejectsRoleTooLong() {
        assertThatThrownBy(() -> new ScenarioConfig("x".repeat(201), null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsSituationTooLong() {
        assertThatThrownBy(() -> new ScenarioConfig(null, "x".repeat(1001)))
                .isInstanceOf(DomainException.class);
    }
}
