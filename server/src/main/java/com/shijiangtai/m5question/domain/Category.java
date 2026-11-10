package com.shijiangtai.m5question.domain;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Category aggregate of a school question bank (04 §5). */
public final class Category {
    public static final int MAX_NAME_LENGTH = 50;

    private final UUID id;
    private final UUID schoolId;
    private final UUID createdBy;
    private final Instant createdAt;
    private String name;
    private int sortOrder;
    private QuestionStatus status;
    private UUID updatedBy;
    private Instant updatedAt;

    private Category(
            UUID id,
            UUID schoolId,
            String name,
            int sortOrder,
            QuestionStatus status,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.schoolId = Objects.requireNonNull(schoolId, "schoolId");
        this.name = requireName(name);
        this.sortOrder = sortOrder;
        this.status = Objects.requireNonNull(status, "status");
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Category create(
            UUID id, UUID schoolId, String name, Integer sortOrder, UUID creator, Instant now) {
        Objects.requireNonNull(now, "now");
        return new Category(id, schoolId, name, sortOrder == null ? 0 : sortOrder,
                QuestionStatus.ACTIVE, creator, creator, now, now);
    }

    public static Category rehydrate(
            UUID id,
            UUID schoolId,
            String name,
            int sortOrder,
            QuestionStatus status,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt) {
        return new Category(id, schoolId, name, sortOrder, status,
                createdBy, updatedBy, createdAt, updatedAt);
    }

    public void update(
            String newName, Integer newSortOrder, QuestionStatus newStatus,
            UUID updater, Instant now) {
        Objects.requireNonNull(now, "now");
        if (newName != null) {
            this.name = requireName(newName);
        }
        if (newSortOrder != null) {
            this.sortOrder = newSortOrder;
        }
        if (newStatus != null) {
            this.status = newStatus;
        }
        this.updatedBy = updater;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == QuestionStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID schoolId() {
        return schoolId;
    }

    public String name() {
        return name;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public QuestionStatus status() {
        return status;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private static String requireName(String value) {
        if (value == null) {
            throw invalid("name");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw invalid("name");
        }
        return trimmed;
    }

    private static DomainException invalid(String field) {
        return new DomainException(ErrorCode.VALIDATION_ERROR,
                "类别名称非法", Map.of("field", field));
    }
}
