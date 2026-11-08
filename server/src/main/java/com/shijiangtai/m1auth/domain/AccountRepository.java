package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.AccountId;
import java.util.Optional;

/** 账号仓储端口（12 §3.1 accounts）。 */
public interface AccountRepository {

    Optional<Account> findById(AccountId id);

    /** 用户名平台唯一；查询前调用方需已 trim。 */
    Optional<Account> findByUsername(String username);

    /** 按规范化后的小写邮箱查询。 */
    Optional<Account> findByEmail(EmailAddress email);

    boolean existsByUsername(String username);

    boolean existsByEmail(EmailAddress email);

    Account save(Account account);
}
