package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 专业（M1 §3.1 / 12 §3.1 majors）。学校内名称唯一；停用不删除（FR-M1-04）。
 */
public record Major(
        UUID id,
        SchoolId schoolId,
        String name,
        ActiveStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_NAME_LENGTH = 100;

    public Major {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(schoolId, "schoolId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = validateName(name);
    }

    public static Major create(UUID id, SchoolId schoolId, String name, Instant now) {
        return new Major(id, schoolId, name, ActiveStatus.ACTIVE, now, now);
    }

    public Major rename(String newName, Instant now) {
        return new Major(id, schoolId, newName, status, createdAt, now);
    }

    public Major disable(Instant now) {
        return new Major(id, schoolId, name, ActiveStatus.INACTIVE, createdAt, now);
    }

    public Major enable(Instant now) {
        return new Major(id, schoolId, name, ActiveStatus.ACTIVE, createdAt, now);
    }

    public boolean isActive() {
        return status == ActiveStatus.ACTIVE;
    }

    private static String validateName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "专业名称不能为空");
        }
        String trimmed = rawName.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "专业名称不能超过 100 个字符");
        }
        return trimmed;
    }
}
