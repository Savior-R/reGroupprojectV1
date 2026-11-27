package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "training_template_publications", schema = "m6_training")
public class TrainingTemplatePublicationEntity {
    @Id
    private UUID id;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(nullable = false)
    private String status;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "unpublished_at")
    private Instant unpublishedAt;

    @Column(name = "unpublish_reason")
    private String unpublishReason;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

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

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public void setCourseId(UUID courseId) {
        this.courseId = courseId;
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

    public String getUnpublishReason() {
        return unpublishReason;
    }

    public void setUnpublishReason(String unpublishReason) {
        this.unpublishReason = unpublishReason;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
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
