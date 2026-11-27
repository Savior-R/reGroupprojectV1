package com.shijiangtai.m6training.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A teacher template published to one course (08 §3.2). */
public final class TrainingTemplatePublication {
    private final UUID id;
    private final UUID templateId;
    private final UUID courseId;
    private final UUID createdBy;
    private final Instant createdAt;
    private TrainingPublicationStatus status;
    private Instant publishedAt;
    private Instant unpublishedAt;
    private TrainingUnpublishReason unpublishReason;
    private Instant updatedAt;

    private TrainingTemplatePublication(
            UUID id,
            UUID templateId,
            UUID courseId,
            TrainingPublicationStatus status,
            Instant publishedAt,
            Instant unpublishedAt,
            TrainingUnpublishReason unpublishReason,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.templateId = Objects.requireNonNull(templateId, "templateId");
        this.courseId = Objects.requireNonNull(courseId, "courseId");
        this.status = Objects.requireNonNull(status, "status");
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        this.unpublishedAt = unpublishedAt;
        this.unpublishReason = unpublishReason;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static TrainingTemplatePublication publish(
            UUID id, UUID templateId, UUID courseId, UUID createdBy, Instant now) {
        return new TrainingTemplatePublication(id, templateId, courseId,
                TrainingPublicationStatus.PUBLISHED, now, null, null, createdBy, now, now);
    }

    public static TrainingTemplatePublication rehydrate(
            UUID id,
            UUID templateId,
            UUID courseId,
            TrainingPublicationStatus status,
            Instant publishedAt,
            Instant unpublishedAt,
            TrainingUnpublishReason unpublishReason,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {
        return new TrainingTemplatePublication(id, templateId, courseId, status, publishedAt,
                unpublishedAt, unpublishReason, createdBy, createdAt, updatedAt);
    }

    /** Idempotent: keeping the first unpublish time and reason. */
    public void unpublish(TrainingUnpublishReason reason, Instant now) {
        if (status == TrainingPublicationStatus.UNPUBLISHED) {
            return;
        }
        status = TrainingPublicationStatus.UNPUBLISHED;
        unpublishedAt = now;
        unpublishReason = Objects.requireNonNull(reason, "reason");
        updatedAt = now;
    }

    public boolean isPublished() {
        return status == TrainingPublicationStatus.PUBLISHED;
    }

    public UUID id() {
        return id;
    }

    public UUID templateId() {
        return templateId;
    }

    public UUID courseId() {
        return courseId;
    }

    public TrainingPublicationStatus status() {
        return status;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public Instant unpublishedAt() {
        return unpublishedAt;
    }

    public TrainingUnpublishReason unpublishReason() {
        return unpublishReason;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
