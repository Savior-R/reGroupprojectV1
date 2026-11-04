package com.shijiangtai.m3classroom.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.DomainException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VirtualStudentConfigTest {

    @Test
    void acceptsBoundaryValues() {
        VirtualStudentConfig student = new VirtualStudentConfig(
                UUID.randomUUID(),
                "x".repeat(20),
                "a".repeat(64),
                "p".repeat(500),
                new BigDecimal("0.00"),
                new BigDecimal("1.00"),
                new BigDecimal("0.50"),
                new BigDecimal("0.50"),
                new BigDecimal("0.50"),
                1,
                1);
        assertThat(student.activity()).isEqualByComparingTo("0.00");
        assertThat(student.questionRate()).isEqualByComparingTo("1.00");
    }

    @Test
    void rejectsParamAboveOne() {
        assertThatThrownBy(() -> studentWithActivity("1.01"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNegativeParam() {
        assertThatThrownBy(() -> studentWithActivity("-0.01"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsThreeDecimalPlaces() {
        assertThatThrownBy(() -> studentWithActivity("0.555"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNameTooLong() {
        assertThatThrownBy(() -> new VirtualStudentConfig(
                UUID.randomUUID(), "x".repeat(21), "avatar-01", "personality",
                new BigDecimal("0.50"), new BigDecimal("0.50"), new BigDecimal("0.50"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), 1, 1))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsSeatZero() {
        assertThatThrownBy(() -> new VirtualStudentConfig(
                UUID.randomUUID(), "林小雨", "avatar-01", "personality",
                new BigDecimal("0.50"), new BigDecimal("0.50"), new BigDecimal("0.50"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), 0, 1))
                .isInstanceOf(DomainException.class);
    }

    private VirtualStudentConfig studentWithActivity(String activity) {
        return new VirtualStudentConfig(
                UUID.randomUUID(), "林小雨", "avatar-01", "personality",
                new BigDecimal(activity), new BigDecimal("0.50"), new BigDecimal("0.50"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), 1, 1);
    }
}
