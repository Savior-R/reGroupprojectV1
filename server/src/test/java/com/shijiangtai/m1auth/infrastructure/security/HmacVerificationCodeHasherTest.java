package com.shijiangtai.m1auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证码 HMAC-SHA256（FR-M1-09：只存哈希；03 §3.2.1）。
 * 邮箱参与加盐，防止同一验证码在不同邮箱间复用。
 */
class HmacVerificationCodeHasherTest {

    private static final String SECRET = "test-secret-32-bytes-minimum-000000";

    @Test
    void isDeterministic() {
        HmacVerificationCodeHasher hasher = new HmacVerificationCodeHasher(SECRET);
        assertThat(hasher.hash("a@b.com", "123456"))
                .isEqualTo(hasher.hash("a@b.com", "123456"));
    }

    @Test
    void producesSixtyFourHexChars() {
        HmacVerificationCodeHasher hasher = new HmacVerificationCodeHasher(SECRET);
        assertThat(hasher.hash("a@b.com", "123456"))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void differentCodesProduceDifferentHashes() {
        HmacVerificationCodeHasher hasher = new HmacVerificationCodeHasher(SECRET);
        assertThat(hasher.hash("a@b.com", "123456"))
                .isNotEqualTo(hasher.hash("a@b.com", "123457"));
    }

    @Test
    void sameCodeOnDifferentEmailProducesDifferentHash() {
        HmacVerificationCodeHasher hasher = new HmacVerificationCodeHasher(SECRET);
        assertThat(hasher.hash("a@b.com", "123456"))
                .isNotEqualTo(hasher.hash("c@d.com", "123456"));
    }

    @Test
    void differentSecretsProduceDifferentHashes() {
        assertThat(new HmacVerificationCodeHasher(SECRET).hash("a@b.com", "123456"))
                .isNotEqualTo(new HmacVerificationCodeHasher(SECRET + "x").hash("a@b.com", "123456"));
    }

    @Test
    void rejectsEmptySecret() {
        assertThatThrownBy(() -> new HmacVerificationCodeHasher(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
