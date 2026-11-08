package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;

/** 密码规则：8—64 位（03 §3.1「校验」）。 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    private PasswordPolicy() {
    }

    /**
     * 校验原始密码长度；不做 trim——空格是合法密码字符。
     *
     * @throws DomainException 长度不在 8—64 之间或为 null
     */
    public static void validate(String rawPassword) {
        if (rawPassword == null) {
            throw invalid("密码不能为空");
        }
        int length = rawPassword.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw invalid("密码长度需为 8—64 位");
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message);
    }
}
