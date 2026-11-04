package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "session_texts", schema = "m3_classroom")
public class SessionTextEntity {
    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "speaker_type", nullable = false, length = 16)
    private String speakerType;

    @Column(name = "speaker_ref")
    private UUID speakerRef;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "start_ms")
    private Long startMs;

    @Column(name = "end_ms")
    private Long endMs;

    @Column(name = "at_ms", nullable = false)
    private long atMs;

    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

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

    public String getSpeakerType() {
        return speakerType;
    }

    public void setSpeakerType(String speakerType) {
        this.speakerType = speakerType;
    }

    public UUID getSpeakerRef() {
        return speakerRef;
    }

    public void setSpeakerRef(UUID speakerRef) {
        this.speakerRef = speakerRef;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Long getStartMs() {
        return startMs;
    }

    public void setStartMs(Long startMs) {
        this.startMs = startMs;
    }

    public Long getEndMs() {
        return endMs;
    }

    public void setEndMs(Long endMs) {
        this.endMs = endMs;
    }

    public long getAtMs() {
        return atMs;
    }

    public void setAtMs(long atMs) {
        this.atMs = atMs;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
