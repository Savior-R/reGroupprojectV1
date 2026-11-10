package com.shijiangtai.m6training.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A student's selected training attempt, kept for one month (08 §3.4). */
public final class TrainingRecord {
    public static final int MAX_ACTIVE_PER_GROUP = 3;
    public static final int RETENTION_MONTHS = 1;

    private final UUID id;
    private final UUID trainingSessionId;
    private final UUID studentAccountId;
    private final UUID templateId;
    private final Instant selectedAt;
    private final Instant expiresAt;
    private final Instant createdAt;
    private TrainingRecordStatus status;
    private Instant deletedAt;
    private Instant updatedAt;

    private TrainingRecord(
            UUID id,
            UUID trainingSessionId,
            UUID studentAccountId,
            UUID templateId,
            Instant selectedAt,
            Instant expiresAt,
            TrainingRecordStatus status,
            Instant deletedAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.trainingSessionId = Objects.requireNonNull(trainingSessionId, "trainingSessionId");
        this.studentAccountId = Objects.requireNonNull(studentAccountId, "studentAccountId");
        this.templateId = templateId;
        this.selectedAt = Objects.requireNonNull(selectedAt, "selectedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(selectedAt)) {
            throw new IllegalArgumentException("expiresAt must be after selectedAt");
        }
        this.status = Objects.requireNonNull(status, "status");
        this.deletedAt = deletedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static TrainingRecord create(
            UUID id,
            UUID trainingSessionId,
            UUID studentAccountId,
            UUID templateId,
            Instant selectedAt,
            Instant expiresAt) {
        return new TrainingRecord(id, trainingSessionId, studentAccountId, templateId,
                selectedAt, expiresAt, TrainingRecordStatus.SELECTED, null,
                selectedAt, selectedAt);
    }

    public static TrainingRecord rehydrate(
            UUID id,
            UUID trainingSessionId,
            UUID studentAccountId,
            UUID templateId,
            Instant selectedAt,
            Instant expiresAt,
            TrainingRecordStatus status,
            Instant deletedAt,
            Instant createdAt,
            Instant updatedAt) {
        return new TrainingRecord(id, trainingSessionId, studentAccountId, templateId,
                selectedAt, expiresAt, status, deletedAt, createdAt, updatedAt);
    }

    public void markEvaluated(Instant now) {
        if (status == TrainingRecordStatus.SELECTED) {
            status = TrainingRecordStatus.EVALUATED;
            updatedAt = now;
        }
    }

    public void markDeleted(Instant now) {
        if (status == TrainingRecordStatus.DELETED) {
            return;
        }
        status = TrainingRecordStatus.DELETED;
        deletedAt = now;
        updatedAt = now;
    }

    public void markExpired(Instant now) {
        if (!status.active()) {
            return;
        }
        status = TrainingRecordStatus.EXPIRED;
        updatedAt = now;
    }

    public boolean activeAt(Instant now) {
        return status.active() && expiresAt.isAfter(now);
    }

    public TrainingRecordStatus effectiveStatus(Instant now) {
        return status.active() && !expiresAt.isAfter(now)
                ? TrainingRecordStatus.EXPIRED : status;
    }

    public UUID id() {
        return id;
    }

    public UUID trainingSessionId() {
        return trainingSessionId;
    }

    public UUID studentAccountId() {
        return studentAccountId;
    }

    public UUID templateId() {
        return templateId;
    }

    public Instant selectedAt() {
        return selectedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public TrainingRecordStatus status() {
        return status;
    }

    public Instant deletedAt() {
        return deletedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
