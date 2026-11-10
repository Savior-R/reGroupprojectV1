package com.shijiangtai.m1auth.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 密码规则：8—64 位（03 §3.1「校验」原文：密码 8—64 位；BCrypt 加密存储）。
 */
class PasswordPolicyTest {

    @Test
    void acceptsEightCharacters() {
        assertThatCode(() -> PasswordPolicy.validate("12345678"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsSixtyFourCharacters() {
        String password = "a".repeat(64);
        assertThatCode(() -> PasswordPolicy.validate(password))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "1234567"})
    void rejectsShorterThanEight(String password) {
        assertThatThrownBy(() -> PasswordPolicy.validate(password))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsLongerThanSixtyFour() {
        String password = "a".repeat(65);
        assertThatThrownBy(() -> PasswordPolicy.validate(password))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
