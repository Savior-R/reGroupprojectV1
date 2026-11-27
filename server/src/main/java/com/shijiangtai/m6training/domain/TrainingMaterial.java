package com.shijiangtai.m6training.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reference material owned by one account, used by free training or a template (08 §9). */
public final class TrainingMaterial {
    public static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;
    public static final int PREVIEW_LENGTH = 500;

    private final UUID id;
    private final UUID ownerAccountId;
    private final TrainingMaterialUsage usage;
    private UUID templateId;
    private final String filename;
    private final String mime;
    private final long size;
    private final String objectKey;
    private final String parsedText;
    private final int charCount;
    private final Instant createdAt;
    private TrainingMaterialStatus status;
    private Instant expiresAt;

    private TrainingMaterial(
            UUID id,
            UUID ownerAccountId,
            TrainingMaterialUsage usage,
            UUID templateId,
            String filename,
            String mime,
            long size,
            String objectKey,
            String parsedText,
            int charCount,
            TrainingMaterialStatus status,
            Instant expiresAt,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.templateId = templateId;
        this.filename = Objects.requireNonNull(filename, "filename");
        this.mime = mime == null ? "application/octet-stream" : mime;
        this.size = size;
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        this.parsedText = parsedText;
        this.charCount = charCount;
        this.status = Objects.requireNonNull(status, "status");
        this.expiresAt = expiresAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static TrainingMaterial create(
            UUID id,
            UUID ownerAccountId,
            TrainingMaterialUsage usage,
            String filename,
            String mime,
            long size,
            String objectKey,
            String parsedText,
            Instant now,
            Instant expiresAt) {
        if (size <= 0) {
            throw new TrainingException(TrainingError.MATERIAL_TYPE_UNSUPPORTED,
                    "材料为空", Map.of("field", "file"));
        }
        if (size > MAX_SIZE_BYTES) {
            throw new TrainingException(TrainingError.MATERIAL_TOO_LARGE,
                    "材料超过 20 MB", Map.of("field", "file"));
        }
        if (usage == TrainingMaterialUsage.FREE_SESSION && expiresAt == null) {
            throw new TrainingException(TrainingError.INTERNAL_ERROR,
                    "自由训练材料缺少过期时间", Map.of());
        }
        return new TrainingMaterial(id, ownerAccountId, usage, null, filename, mime, size,
                objectKey, parsedText, parsedText == null ? 0 : parsedText.length(),
                TrainingMaterialStatus.UPLOADED, expiresAt, now);
    }

    public static TrainingMaterial rehydrate(
            UUID id,
            UUID ownerAccountId,
            TrainingMaterialUsage usage,
            UUID templateId,
            String filename,
            String mime,
            long size,
            String objectKey,
            String parsedText,
            int charCount,
            TrainingMaterialStatus status,
            Instant expiresAt,
            Instant createdAt) {
        return new TrainingMaterial(id, ownerAccountId, usage, templateId, filename, mime, size,
                objectKey, parsedText, charCount, status, expiresAt, createdAt);
    }

    /** Attaches a template-usage material; expiry is cleared once attached. */
    public void attachTo(UUID templateId) {
        if (usage != TrainingMaterialUsage.TEMPLATE) {
            throw new TrainingException(TrainingError.TRAINING_MATERIAL_INVALID,
                    "自由训练材料不能附着到模板", Map.of("material_id", id.toString()));
        }
        this.templateId = Objects.requireNonNull(templateId, "templateId");
        this.status = TrainingMaterialStatus.ATTACHED;
        this.expiresAt = null;
    }

    public void markUsed() {
        if (status == TrainingMaterialStatus.UPLOADED) {
            status = TrainingMaterialStatus.USED;
        }
    }

    public void markDeleted() {
        status = TrainingMaterialStatus.DELETED;
    }

    public void markExpired() {
        if (status == TrainingMaterialStatus.UPLOADED) {
            status = TrainingMaterialStatus.EXPIRED;
        }
    }

    public boolean usableAt(Instant now) {
        return status == TrainingMaterialStatus.UPLOADED
                && expiresAt != null && expiresAt.isAfter(now);
    }

    public String preview() {
        if (parsedText == null) {
            return "";
        }
        return parsedText.length() <= PREVIEW_LENGTH
                ? parsedText : parsedText.substring(0, PREVIEW_LENGTH);
    }

    public UUID id() {
        return id;
    }

    public UUID ownerAccountId() {
        return ownerAccountId;
    }

    public TrainingMaterialUsage usage() {
        return usage;
    }

    public UUID templateId() {
        return templateId;
    }

    public String filename() {
        return filename;
    }

    public String mime() {
        return mime;
    }

    public long size() {
        return size;
    }

    public String objectKey() {
        return objectKey;
    }

    public String parsedText() {
        return parsedText;
    }

    public int charCount() {
        return charCount;
    }

    public TrainingMaterialStatus status() {
        return status;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
