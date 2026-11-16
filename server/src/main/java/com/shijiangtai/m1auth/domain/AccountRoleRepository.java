package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.util.Set;

/**
 * 账号授权角色（{@code m1_auth.account_roles}，V14）。
 *
 * <p>与 {@link Account#type()} 的区别：
 * <ul>
 *   <li>{@code Account.type()} 是<b>主身份</b>，注册时确认、决定档案类型、不可变更
 *   <li>本仓储返回的是<b>授权角色集合</b>，可在主身份之外叠加（如教师兼管理员），
 *       授权判定取并集
 * </ul>
 */
public interface AccountRoleRepository {

    /** 账号的全部角色；至少包含主身份角色（V14 回填保证）。 */
    Set<AccountType> findRoles(AccountId accountId);

    /** 授予附加角色；已存在则幂等。 */
    void grant(AccountId accountId, AccountType role, AccountId grantedBy, Instant now);

    /** 撤销附加角色；主身份角色不应通过本方法移除。 */
    void revoke(AccountId accountId, AccountType role);
}
