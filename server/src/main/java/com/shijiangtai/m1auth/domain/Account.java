package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.Objects;

/**
 * 账号聚合（M1 §3.1 / 12 §3.1 accounts）。
 *
 * <ul>
 *   <li>账号类型注册时确认，注册后不可变更——本类不提供变更方法，也没有对应用例
 *   <li>{@code email} 可空；{@code emailVerifiedAt} 为空时不可用于验证码登录（FR-M1-09）
 *   <li>管理员建校前 {@code schoolId} 为空，建校后仍以 {@code school_admins} 为准（03 §3.4）
 * </ul>
 */
public record Account(
        AccountId id,
        String username,
        String passwordHash,
        EmailAddress email,
        Instant emailVerifiedAt,
        AccountType type,
        SchoolId schoolId,
        Instant createdAt,
        Instant updatedAt) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * 注册新账号。
     *
     * @param schoolId 学生 / 教师必填；管理员建校前为 {@code null}
     */
    public static Account register(
            AccountId id,
            String username,
            String passwordHash,
            EmailAddress email,
            Instant emailVerifiedAt,
            AccountType type,
            SchoolId schoolId,
            Instant now) {
        return new Account(
                id, username, passwordHash, email, emailVerifiedAt, type, schoolId, now, now);
    }

    /** 绑定 / 换绑邮箱：验证码校验通过后才写入，{@code email_verified_at} 同步更新。 */
    public Account bindVerifiedEmail(EmailAddress newEmail, Instant now) {
        return new Account(
                id, username, passwordHash, newEmail, now, type, schoolId, createdAt, now);
    }

    /** 解绑邮箱；解绑后不能再用于验证码登录。 */
    public Account unbindEmail(Instant now) {
        return new Account(id, username, passwordHash, null, null, type, schoolId, createdAt, now);
    }

    /** 只有已绑定且已验证的邮箱可用于验证码登录（FR-M1-09）。 */
    public boolean canLoginByEmail() {
        return email != null && emailVerifiedAt != null;
    }

    /** 是否仍保留密码登录能力（密码注册即有，故恒为真；保留给解绑校验表达语义）。 */
    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }
}
