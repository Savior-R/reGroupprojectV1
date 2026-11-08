package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.Optional;

/**
 * 管理员-学校绑定端口（12 §3.1 school_admins）。MVP 一对一：
 * 部分唯一索引 {@code UNIQUE(account_id) WHERE status='active'} 与
 * {@code UNIQUE(school_id) WHERE status='active'} 兜底（03 §3.4）。
 */
public interface SchoolAdminRepository {

    Optional<SchoolId> findActiveSchoolIdByAccount(AccountId accountId);

    boolean existsActiveBySchool(SchoolId schoolId);

    void insert(SchoolId schoolId, AccountId accountId, boolean owner, Instant now);

    /** 学校管理员账号（一校一人）。 */
    Optional<AccountId> findActiveAccountBySchool(SchoolId schoolId);
}
