package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 教师专业归属（M1 §3.1 / 12 §3.1 teacher_majors）。
 *
 * <p>变更时旧行标记 {@code INACTIVE} 并写 {@code effective_to}，保留历史；
 * 部分唯一索引保证同一专业只有一条 active（FR-M1-08）。
 */
public record TeacherMajorAssignment(
        UUID id,
        AccountId accountId,
        UUID majorId,
        ActiveStatus status,
        Instant effectiveFrom,
        Instant effectiveTo,
        Instant createdAt,
        Instant updatedAt) {

    public TeacherMajorAssignment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(majorId, "majorId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static TeacherMajorAssignment active(
            UUID id, AccountId accountId, UUID majorId, Instant now) {
        return new TeacherMajorAssignment(
                id, accountId, majorId, ActiveStatus.ACTIVE, now, null, now, now);
    }

    /** 管理员修改专业范围时，把旧关系置为失效并记录失效时间。 */
    public TeacherMajorAssignment expire(Instant now) {
        return new TeacherMajorAssignment(
                id, accountId, majorId, ActiveStatus.INACTIVE, effectiveFrom, now, createdAt, now);
    }

    public boolean isActive() {
        return status == ActiveStatus.ACTIVE;
    }
}
