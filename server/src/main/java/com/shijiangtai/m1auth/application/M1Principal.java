package com.shijiangtai.m1auth.application;

import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.Set;

/**
 * M1 当前用户（M1 专用）。与 shared 的 {@code CurrentUser} 的区别：
 * 管理员未建校时 {@code schoolId} 允许为空（03 §3.2「管理员未建校：school_id 为空」）。
 *
 * <p>{@code type} 是主身份（注册时确认、不可变更）；{@code roles} 是本次 token 的
 * 授权角色集合，可能多于一个（教师兼管理员，V14）。判断「能不能做管理员的事」必须用
 * {@link #isAdmin()}，不能用 {@code type}——否则被授予管理员的教师会被误拒。
 */
public record M1Principal(
        AccountId accountId,
        AccountType type,
        SchoolId schoolId,
        Set<AccountType> roles) {

    public M1Principal {
        roles = roles == null || roles.isEmpty() ? Set.of(type) : Set.copyOf(roles);
    }

    /** 单角色构造：主身份即唯一角色（兼容旧调用与 dev / 测试的头身份）。 */
    public M1Principal(AccountId accountId, AccountType type, SchoolId schoolId) {
        this(accountId, type, schoolId, type == null ? Set.of() : Set.of(type));
    }

    public boolean hasRole(AccountType role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole(AccountType.ADMIN);
    }

    public boolean isAdminWithoutSchool() {
        return isAdmin() && schoolId == null;
    }
}
