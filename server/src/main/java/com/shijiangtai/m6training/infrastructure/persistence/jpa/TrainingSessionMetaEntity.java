package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "training_session_meta", schema = "m6_training")
public class TrainingSessionMetaEntity {
    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "training_level", nullable = false)
    private String trainingLevel;

    @Column(name = "effective_config_snapshot", nullable = false, columnDefinition = "jsonb")
    private String effectiveConfigSnapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public String getTrainingLevel() {
        return trainingLevel;
    }

    public void setTrainingLevel(String trainingLevel) {
        this.trainingLevel = trainingLevel;
    }

    public String getEffectiveConfigSnapshot() {
        return effectiveConfigSnapshot;
    }

    public void setEffectiveConfigSnapshot(String effectiveConfigSnapshot) {
        this.effectiveConfigSnapshot = effectiveConfigSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
