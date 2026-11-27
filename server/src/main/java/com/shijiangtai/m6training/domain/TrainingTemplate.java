package com.shijiangtai.m6training.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Per-account personal training template (08 §3.1). */
public final class TrainingTemplate {
    private final UUID id;
    private final UUID schoolId;
    private final UUID ownerAccountId;
    private final TrainingOwnerRole ownerRole;
    private final TrainingTemplateSourceType sourceType;
    private final UUID sourceTaskId;
    private final Integer sourceTaskConfigVersion;
    private final UUID sourceTemplateId;
    private final UUID sourcePublicationId;
    private final Instant createdAt;
    private String name;
    private TrainingLevel trainingLevel;
    private TrainingConfigSnapshot config;
    private TrainingTemplateStatus status;
    private Instant archivedAt;
    private Instant updatedAt;

    private TrainingTemplate(
            UUID id,
            UUID schoolId,
            UUID ownerAccountId,
            TrainingOwnerRole ownerRole,
            String name,
            TrainingLevel trainingLevel,
            TrainingConfigSnapshot config,
            TrainingTemplateSourceType sourceType,
            UUID sourceTaskId,
            Integer sourceTaskConfigVersion,
            UUID sourceTemplateId,
            UUID sourcePublicationId,
            TrainingTemplateStatus status,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.schoolId = Objects.requireNonNull(schoolId, "schoolId");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        this.ownerRole = Objects.requireNonNull(ownerRole, "ownerRole");
        this.name = requireName(name);
        this.trainingLevel = Objects.requireNonNull(trainingLevel, "trainingLevel");
        this.config = Objects.requireNonNull(config, "config");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.sourceTaskId = sourceTaskId;
        this.sourceTaskConfigVersion = sourceTaskConfigVersion;
        this.sourceTemplateId = sourceTemplateId;
        this.sourcePublicationId = sourcePublicationId;
        this.status = Objects.requireNonNull(status, "status");
        this.archivedAt = archivedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static TrainingTemplate createManual(
            UUID id,
            UUID schoolId,
            UUID ownerAccountId,
            TrainingOwnerRole ownerRole,
            String name,
            TrainingLevel trainingLevel,
            TrainingConfigSnapshot config,
            Instant now) {
        return new TrainingTemplate(id, schoolId, ownerAccountId, ownerRole, name,
                trainingLevel, config, TrainingTemplateSourceType.MANUAL,
                null, null, null, null, TrainingTemplateStatus.ACTIVE, null, now, now);
    }

    public static TrainingTemplate createCopy(
            UUID id,
            UUID schoolId,
            UUID ownerAccountId,
            String name,
            TrainingLevel trainingLevel,
            TrainingConfigSnapshot config,
            UUID sourceTemplateId,
            UUID sourcePublicationId,
            Instant now) {
        return new TrainingTemplate(id, schoolId, ownerAccountId, TrainingOwnerRole.STUDENT,
                name, trainingLevel, config, TrainingTemplateSourceType.TEMPLATE,
                null, null, sourceTemplateId, sourcePublicationId,
                TrainingTemplateStatus.ACTIVE, null, now, now);
    }

    public static TrainingTemplate rehydrate(
            UUID id,
            UUID schoolId,
            UUID ownerAccountId,
            TrainingOwnerRole ownerRole,
            String name,
            TrainingLevel trainingLevel,
            TrainingConfigSnapshot config,
            TrainingTemplateSourceType sourceType,
            UUID sourceTaskId,
            Integer sourceTaskConfigVersion,
            UUID sourceTemplateId,
            UUID sourcePublicationId,
            TrainingTemplateStatus status,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt) {
        return new TrainingTemplate(id, schoolId, ownerAccountId, ownerRole, name,
                trainingLevel, config, sourceType, sourceTaskId, sourceTaskConfigVersion,
                sourceTemplateId, sourcePublicationId, status, archivedAt, createdAt, updatedAt);
    }

    /** Rename always allowed; configuration fields are locked by the service while published. */
    public void rename(String name, Instant now) {
        this.name = requireName(name);
        this.updatedAt = now;
    }

    public void updateConfig(
            String name,
            TrainingLevel trainingLevel,
            TrainingConfigSnapshot config,
            Instant now) {
        this.name = requireName(name);
        this.trainingLevel = Objects.requireNonNull(trainingLevel, "trainingLevel");
        this.config = Objects.requireNonNull(config, "config");
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        if (status == TrainingTemplateStatus.ARCHIVED) {
            return;
        }
        status = TrainingTemplateStatus.ARCHIVED;
        archivedAt = now;
        updatedAt = now;
    }

    public boolean isActive() {
        return status == TrainingTemplateStatus.ACTIVE;
    }

    public boolean isOwnedBy(UUID accountId) {
        return ownerAccountId.equals(accountId);
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw invalid(Map.of("field", "name"), "模板名称不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > TrainingDefaults.MAX_NAME_LENGTH) {
            throw invalid(Map.of("field", "name"), "模板名称不能超过 100 字");
        }
        return trimmed;
    }

    private static TrainingException invalid(Map<String, Object> details, String message) {
        return new TrainingException(TrainingError.TRAINING_TEMPLATE_INVALID, message, details);
    }

    public UUID id() {
        return id;
    }

    public UUID schoolId() {
        return schoolId;
    }

    public UUID ownerAccountId() {
        return ownerAccountId;
    }

    public TrainingOwnerRole ownerRole() {
        return ownerRole;
    }

    public String name() {
        return name;
    }

    public TrainingLevel trainingLevel() {
        return trainingLevel;
    }

    public String classroomMode() {
        return TrainingDefaults.CLASSROOM_MODE;
    }

    public TrainingConfigSnapshot config() {
        return config;
    }

    public TrainingTemplateSourceType sourceType() {
        return sourceType;
    }

    public UUID sourceTaskId() {
        return sourceTaskId;
    }

    public Integer sourceTaskConfigVersion() {
        return sourceTaskConfigVersion;
    }

    public UUID sourceTemplateId() {
        return sourceTemplateId;
    }

    public UUID sourcePublicationId() {
        return sourcePublicationId;
    }

    public TrainingTemplateStatus status() {
        return status;
    }

    public Instant archivedAt() {
        return archivedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
