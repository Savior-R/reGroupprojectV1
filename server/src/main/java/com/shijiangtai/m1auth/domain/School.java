package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.Objects;

/**
 * 学校（M1 §3.1 / 12 §3.1 schools）。名称平台唯一、非空、≤100 字符；管理员与学校一对一（FR-M1-03）。
 */
public record School(SchoolId id, String name, Instant createdAt, Instant updatedAt) {

    public static final int MAX_NAME_LENGTH = 100;

    public School {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = validateName(name);
    }

    public static School create(SchoolId id, String name, Instant now) {
        return new School(id, name, now, now);
    }

    public School rename(String newName, Instant now) {
        return new School(id, newName, createdAt, now);
    }

    private static String validateName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "学校名称不能为空");
        }
        String trimmed = rawName.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "学校名称不能超过 100 个字符");
        }
        return trimmed;
    }
}
