package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 邮箱值对象：写入前 trim + 小写，列宽 254（FR-M1-09、03 §3.2.1）。
 * 统一小写后 {@code lower(email)} 唯一索引才有意义。
 */
public record EmailAddress(String value) {

    public static final int MAX_LENGTH = 254;

    /** 简化 RFC 5322：local 允许常见符号，域名必须有至少两段且 TLD 为字母。 */
    private static final Pattern PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    public EmailAddress {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > MAX_LENGTH || !PATTERN.matcher(value).matches()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "邮箱格式不正确");
        }
    }

    /**
     * @throws DomainException 为 null、空、超长或格式非法
     */
    public static EmailAddress of(String rawEmail) {
        if (rawEmail == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "邮箱不能为空");
        }
        String normalized = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "邮箱不能为空");
        }
        if (normalized.length() > MAX_LENGTH) {
            throw new DomainException(
                    ErrorCode.VALIDATION_ERROR, "邮箱长度不能超过 " + MAX_LENGTH + " 个字符");
        }
        return new EmailAddress(normalized);
    }
}
