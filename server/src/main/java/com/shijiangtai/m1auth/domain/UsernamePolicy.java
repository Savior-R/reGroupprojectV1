package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;

/**
 * 用户名规则：即真实姓名，平台内唯一，写入前 trim（03 §3.1、FR-M1-01）。
 * 列宽 {@code varchar(64)}，按字符数（code point）计。
 */
public final class UsernamePolicy {

    public static final int MAX_LENGTH = 64;

    private UsernamePolicy() {
    }

    /** 去掉首尾空白；null 原样返回，交由 {@link #validate} 拒绝。 */
    public static String normalize(String rawUsername) {
        return rawUsername == null ? null : rawUsername.trim();
    }

    /**
     * @throws DomainException 为 null、trim 后为空、超长或含控制字符
     */
    public static void validate(String rawUsername) {
        if (rawUsername == null) {
            throw invalid("用户名不能为空");
        }
        String normalized = rawUsername.trim();
        if (normalized.isEmpty()) {
            throw invalid("用户名不能为空");
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_LENGTH) {
            throw invalid("用户名不能超过 " + MAX_LENGTH + " 个字符");
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) {
                throw invalid("用户名不能包含控制字符");
            }
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message);
    }
}
