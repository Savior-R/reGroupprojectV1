package com.shijiangtai.m2course.domain;

import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.util.Objects;

/** 课程老师组成员关系。组内平权，不设主教师；移出为软删除。 */
public final class CourseTeacher {
    private final AccountId accountId;
    private final Instant joinedAt;
    private TeacherStatus status;
    private Instant leftAt;

    private CourseTeacher(AccountId accountId, Instant joinedAt, TeacherStatus status, Instant leftAt) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.leftAt = leftAt;
    }

    public static CourseTeacher active(AccountId accountId, Instant joinedAt) {
        return new CourseTeacher(accountId, joinedAt, TeacherStatus.ACTIVE, null);
    }

    public static CourseTeacher restore(
            AccountId accountId, Instant joinedAt, TeacherStatus status, Instant leftAt) {
        return new CourseTeacher(accountId, joinedAt, status, leftAt);
    }

    public void markRemoved(Instant now) {
        this.status = TeacherStatus.REMOVED;
        this.leftAt = Objects.requireNonNull(now, "now");
    }

    public void rejoin(Instant now) {
        this.status = TeacherStatus.ACTIVE;
        this.leftAt = null;
    }

    public boolean isActive() {
        return status == TeacherStatus.ACTIVE;
    }

    public AccountId accountId() {
        return accountId;
    }

    public Instant joinedAt() {
        return joinedAt;
    }

    public TeacherStatus status() {
        return status;
    }

    public Instant leftAt() {
        return leftAt;
    }
}
