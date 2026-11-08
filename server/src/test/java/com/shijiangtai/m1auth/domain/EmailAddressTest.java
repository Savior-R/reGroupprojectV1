package com.shijiangtai.m1auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * 邮箱规范化与校验：写入前 trim + 小写，平台内唯一（FR-M1-09、03 §3.2.1）。
 */
class EmailAddressTest {

    @Test
    void trimsAndLowercases() {
        assertThat(EmailAddress.of("  User.Name@Example.COM ").value())
                .isEqualTo("user.name@example.com");
    }

    @Test
    void acceptsPlusTagAndSubdomain() {
        assertThat(EmailAddress.of("a+b@mail.school.edu.cn").value())
                .isEqualTo("a+b@mail.school.edu.cn");
    }

    @Test
    void rejectsMissingAt() {
        assertThatThrownBy(() -> EmailAddress.of("user.example.com"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsMissingDomainSuffix() {
        assertThatThrownBy(() -> EmailAddress.of("user@example"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> EmailAddress.of(null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsLongerThanColumnWidth() {
        String local = "a".repeat(250);
        assertThatThrownBy(() -> EmailAddress.of(local + "@ex.com"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
