package com.shijiangtai.m2course.domain;

import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.util.Objects;

/** 课程学生成员关系。退出 / 移出为软删除，重新加入生成新的有效关系。 */
public final class CourseStudent {
    private final AccountId accountId;
    private final JoinMethod joinMethod;
    private final Instant joinedAt;
    private StudentStatus status;
    private Instant leftAt;

    private CourseStudent(
            AccountId accountId,
            JoinMethod joinMethod,
            Instant joinedAt,
            StudentStatus status,
            Instant leftAt) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.joinMethod = Objects.requireNonNull(joinMethod, "joinMethod");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.leftAt = leftAt;
    }

    public static CourseStudent active(AccountId accountId, JoinMethod joinMethod, Instant joinedAt) {
        return new CourseStudent(accountId, joinMethod, joinedAt, StudentStatus.ACTIVE, null);
    }

    public static CourseStudent restore(
            AccountId accountId,
            JoinMethod joinMethod,
            Instant joinedAt,
            StudentStatus status,
            Instant leftAt) {
        return new CourseStudent(accountId, joinMethod, joinedAt, status, leftAt);
    }

    public void markLeft(Instant now) {
        this.status = StudentStatus.LEFT;
        this.leftAt = Objects.requireNonNull(now, "now");
    }

    public void markRemoved(Instant now) {
        this.status = StudentStatus.REMOVED;
        this.leftAt = Objects.requireNonNull(now, "now");
    }

    public boolean isActive() {
        return status == StudentStatus.ACTIVE;
    }

    public AccountId accountId() {
        return accountId;
    }

    public JoinMethod joinMethod() {
        return joinMethod;
    }

    public Instant joinedAt() {
        return joinedAt;
    }

    public StudentStatus status() {
        return status;
    }

    public Instant leftAt() {
        return leftAt;
    }
}
