package com.shijiangtai.m1auth.application;

import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;

/**
 * M1 当前用户（M1 专用）。与 shared 的 {@code CurrentUser} 的区别：
 * 管理员未建校时 {@code schoolId} 允许为空（03 §3.2「管理员未建校：school_id 为空」）。
 */
public record M1Principal(AccountId accountId, AccountType type, SchoolId schoolId) {

    public boolean isAdminWithoutSchool() {
        return type == AccountType.ADMIN && schoolId == null;
    }
}
