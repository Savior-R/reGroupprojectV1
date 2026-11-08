package com.shijiangtai.m1auth.domain;

/**
 * 邮箱验证码哈希端口（FR-M1-09：服务端只存 HMAC / 哈希，不存明文）。
 * 实现见 infrastructure.security.HmacVerificationCodeHasher。
 */
public interface VerificationCodeHasher {

    /**
     * 对邮箱与验证码做带盐哈希；同一 email + code 必须稳定。
     *
     * @param email 已规范化的邮箱（小写）
     * @param code  6 位明文验证码
     * @return 十六进制哈希串
     */
    String hash(String email, String code);
}
