package com.shijiangtai.shared.application;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.Objects;

public record CurrentUser(AccountId accountId, SchoolId schoolId, AccountType type) {
    public CurrentUser {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(schoolId, "schoolId");
        Objects.requireNonNull(type, "type");
    }
}
