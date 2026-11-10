package com.shijiangtai.m5question.application;

import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;
import java.util.Objects;

/** Role gate for M5 (04 §8): students never reach the question bank. */
final class M5Access {
    private M5Access() {
    }

    static CurrentUser requireReader(CurrentUser user) {
        Objects.requireNonNull(user, "user");
        if (user.type() == AccountType.STUDENT) {
            throw new DomainException(ErrorCode.ROLE_NOT_ALLOWED,
                    "学生无权访问题库", Map.of("role", user.type().name()));
        }
        return user;
    }

    static CurrentUser requireManager(CurrentUser user) {
        CurrentUser checked = requireReader(user);
        if (checked.type() != AccountType.TEACHER && checked.type() != AccountType.ADMIN) {
            throw new DomainException(ErrorCode.ROLE_NOT_ALLOWED,
                    "无权管理题库", Map.of("role", checked.type().name()));
        }
        return checked;
    }
}
