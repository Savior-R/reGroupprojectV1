package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "frames", schema = "m3_classroom")
public class SessionFrameEntity {
    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "timestamp_ms", nullable = false)
    private long timestampMs;

    @Column(columnDefinition = "text")
    private String text;

    @Column(name = "bounding_boxes", columnDefinition = "jsonb")
    private String boundingBoxes;

    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(name = "change_score", precision = 5, scale = 4)
    private BigDecimal changeScore;

    @Column(name = "frame_type", nullable = false, length = 24)
    private String frameType;

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public void setTimestampMs(long timestampMs) {
        this.timestampMs = timestampMs;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getBoundingBoxes() {
        return boundingBoxes;
    }

    public void setBoundingBoxes(String boundingBoxes) {
        this.boundingBoxes = boundingBoxes;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public BigDecimal getChangeScore() {
        return changeScore;
    }

    public void setChangeScore(BigDecimal changeScore) {
        this.changeScore = changeScore;
    }

    public String getFrameType() {
        return frameType;
    }

    public void setFrameType(String frameType) {
        this.frameType = frameType;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
