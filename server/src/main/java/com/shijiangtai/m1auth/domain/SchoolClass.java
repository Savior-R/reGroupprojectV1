package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 班级（M1 §3.1 / 12 §3.1 classes）。自然键 {@code (major_id, grade, name)} 唯一；
 * 年级指入学届别（如 2024 级），由管理员导入，学生只选择不输入（FR-M1-05）。
 */
public record SchoolClass(
        UUID id,
        SchoolId schoolId,
        UUID majorId,
        String grade,
        String name,
        ActiveStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_GRADE_LENGTH = 20;
    public static final int MAX_NAME_LENGTH = 50;

    public SchoolClass {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(schoolId, "schoolId");
        Objects.requireNonNull(majorId, "majorId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        grade = requireText(grade, MAX_GRADE_LENGTH, "年级");
        name = requireText(name, MAX_NAME_LENGTH, "班名");
    }

    public static SchoolClass create(
            UUID id, SchoolId schoolId, UUID majorId, String grade, String name, Instant now) {
        return new SchoolClass(
                id, schoolId, majorId, grade, name, ActiveStatus.ACTIVE, now, now);
    }

    /** 已有学生的班级不允许改专业 / 年级，由 application 层校验（FR-M1-04）。 */
    public SchoolClass update(UUID newMajorId, String newGrade, String newName, Instant now) {
        return new SchoolClass(
                id, schoolId, newMajorId, newGrade, newName, status, createdAt, now);
    }

    public SchoolClass disable(Instant now) {
        return new SchoolClass(id, schoolId, majorId, grade, name, ActiveStatus.INACTIVE,
                createdAt, now);
    }

    public boolean isActive() {
        return status == ActiveStatus.ACTIVE;
    }

    private static String requireText(String raw, int maxLength, String label) {
        if (raw == null || raw.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, label + "不能为空");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new DomainException(
                    ErrorCode.VALIDATION_ERROR, label + "不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }
}
