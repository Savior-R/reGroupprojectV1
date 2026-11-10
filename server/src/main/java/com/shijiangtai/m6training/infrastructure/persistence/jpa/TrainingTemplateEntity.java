package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "training_templates", schema = "m6_training")
public class TrainingTemplateEntity {
    @Id
    private UUID id;

    @Column(name = "source_task_id", nullable = false)
    private UUID sourceTaskId;

    @Column(name = "source_task_config_version", nullable = false)
    private int sourceTaskConfigVersion;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "training_level", nullable = false)
    private String trainingLevel;

    @Column(name = "classroom_mode", nullable = false)
    private String classroomMode;

    @Column(name = "config_snapshot", nullable = false, columnDefinition = "jsonb")
    private String configSnapshot;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private String status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "unpublished_at")
    private Instant unpublishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSourceTaskId() {
        return sourceTaskId;
    }

    public void setSourceTaskId(UUID sourceTaskId) {
        this.sourceTaskId = sourceTaskId;
    }

    public int getSourceTaskConfigVersion() {
        return sourceTaskConfigVersion;
    }

    public void setSourceTaskConfigVersion(int sourceTaskConfigVersion) {
        this.sourceTaskConfigVersion = sourceTaskConfigVersion;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public void setCourseId(UUID courseId) {
        this.courseId = courseId;
    }

    public String getTrainingLevel() {
        return trainingLevel;
    }

    public void setTrainingLevel(String trainingLevel) {
        this.trainingLevel = trainingLevel;
    }

    public String getClassroomMode() {
        return classroomMode;
    }

    public void setClassroomMode(String classroomMode) {
        this.classroomMode = classroomMode;
    }

    public String getConfigSnapshot() {
        return configSnapshot;
    }

    public void setConfigSnapshot(String configSnapshot) {
        this.configSnapshot = configSnapshot;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getUnpublishedAt() {
        return unpublishedAt;
    }

    public void setUnpublishedAt(Instant unpublishedAt) {
        this.unpublishedAt = unpublishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
