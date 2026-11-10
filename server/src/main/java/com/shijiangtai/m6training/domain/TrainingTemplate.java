package com.shijiangtai.m6training.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Teacher-published training template (08 §3.1). */
public final class TrainingTemplate {
    private final UUID id;
    private final UUID sourceTaskId;
    private final int sourceTaskConfigVersion;
    private final UUID courseId;
    private final TrainingLevel trainingLevel;
    private final String classroomMode;
    private final TrainingConfigSnapshot config;
    private final UUID createdBy;
    private final Instant createdAt;
    private TrainingTemplateStatus status;
    private Instant publishedAt;
    private Instant unpublishedAt;
    private Instant updatedAt;

    private TrainingTemplate(
            UUID id,
            UUID sourceTaskId,
            int sourceTaskConfigVersion,
            UUID courseId,
            TrainingLevel trainingLevel,
            String classroomMode,
            TrainingConfigSnapshot config,
            UUID createdBy,
            TrainingTemplateStatus status,
            Instant publishedAt,
            Instant unpublishedAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceTaskId = Objects.requireNonNull(sourceTaskId, "sourceTaskId");
        this.sourceTaskConfigVersion = sourceTaskConfigVersion;
        this.courseId = Objects.requireNonNull(courseId, "courseId");
        this.trainingLevel = Objects.requireNonNull(trainingLevel, "trainingLevel");
        this.classroomMode = Objects.requireNonNull(classroomMode, "classroomMode");
        this.config = Objects.requireNonNull(config, "config");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.status = Objects.requireNonNull(status, "status");
        this.publishedAt = publishedAt;
        this.unpublishedAt = unpublishedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static TrainingTemplate create(
            UUID id,
            UUID sourceTaskId,
            int sourceTaskConfigVersion,
            UUID courseId,
            TrainingLevel trainingLevel,
            TrainingConfigSnapshot config,
            UUID createdBy,
            Instant now) {
        return new TrainingTemplate(
                id, sourceTaskId, sourceTaskConfigVersion, courseId, trainingLevel,
                TrainingDefaults.CLASSROOM_MODE, config, createdBy,
                TrainingTemplateStatus.PUBLISHED, now, null, now, now);
    }

    public static TrainingTemplate rehydrate(
            UUID id,
            UUID sourceTaskId,
            int sourceTaskConfigVersion,
            UUID courseId,
            TrainingLevel trainingLevel,
            String classroomMode,
            TrainingConfigSnapshot config,
            UUID createdBy,
            TrainingTemplateStatus status,
            Instant publishedAt,
            Instant unpublishedAt,
            Instant createdAt,
            Instant updatedAt) {
        return new TrainingTemplate(id, sourceTaskId, sourceTaskConfigVersion, courseId,
                trainingLevel, classroomMode, config, createdBy, status, publishedAt,
                unpublishedAt, createdAt, updatedAt);
    }

    /** Idempotent: unpublishing twice keeps the first timestamp. */
    public void unpublish(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status == TrainingTemplateStatus.UNPUBLISHED) {
            return;
        }
        status = TrainingTemplateStatus.UNPUBLISHED;
        unpublishedAt = now;
        updatedAt = now;
    }

    public boolean isPublished() {
        return status == TrainingTemplateStatus.PUBLISHED;
    }

    public UUID id() {
        return id;
    }

    public UUID sourceTaskId() {
        return sourceTaskId;
    }

    public int sourceTaskConfigVersion() {
        return sourceTaskConfigVersion;
    }

    public UUID courseId() {
        return courseId;
    }

    public TrainingLevel trainingLevel() {
        return trainingLevel;
    }

    public String classroomMode() {
        return classroomMode;
    }

    public TrainingConfigSnapshot config() {
        return config;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public TrainingTemplateStatus status() {
        return status;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public Instant unpublishedAt() {
        return unpublishedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
