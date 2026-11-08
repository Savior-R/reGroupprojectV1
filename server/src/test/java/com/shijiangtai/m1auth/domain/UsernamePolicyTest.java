package com.shijiangtai.m1auth.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * 用户名规则：即真实姓名，平台内唯一，写入前 trim（03 §3.1、FR-M1-01）。
 */
class UsernamePolicyTest {

    @Test
    void acceptsTrimmedNameAndReturnsCanonicalValue() {
        // 写入前 trim，DB 存 trim 后的值
        org.assertj.core.api.Assertions.assertThat(UsernamePolicy.normalize("  张三  "))
                .isEqualTo("张三");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> UsernamePolicy.validate("   "))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> UsernamePolicy.validate(null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void acceptsNameAtMaxLength() {
        String name = "名".repeat(64);
        assertThatCode(() -> UsernamePolicy.validate(name)).doesNotThrowAnyException();
    }

    @Test
    void rejectsLongerThanColumnWidth() {
        String name = "名".repeat(65);
        assertThatThrownBy(() -> UsernamePolicy.validate(name))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsControlCharacters() {
        assertThatThrownBy(() -> UsernamePolicy.validate("张\u0000三"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
