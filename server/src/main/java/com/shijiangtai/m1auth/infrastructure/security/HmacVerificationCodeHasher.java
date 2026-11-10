package com.shijiangtai.m1auth.infrastructure.security;

import com.shijiangtai.m1auth.domain.VerificationCodeHasher;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 验证码哈希（FR-M1-09：服务端只存 HMAC，不存明文）。
 * 邮箱参与消息体，使同一验证码在不同邮箱下产生不同哈希。
 */
public class HmacVerificationCodeHasher implements VerificationCodeHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public HmacVerificationCodeHasher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("验证码哈希密钥不能为空");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String hash(String email, String code) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] digest = mac.doFinal((email + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 " + ALGORITHM, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("验证码哈希失败", e);
        }
    }
}
