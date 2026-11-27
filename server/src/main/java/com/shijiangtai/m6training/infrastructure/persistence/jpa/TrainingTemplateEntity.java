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

    @Column(name = "school_id", nullable = false)
    private UUID schoolId;

    @Column(name = "owner_account_id", nullable = false)
    private UUID ownerAccountId;

    @Column(name = "owner_role", nullable = false)
    private String ownerRole;

    @Column(nullable = false)
    private String name;

    @Column(name = "training_level", nullable = false)
    private String trainingLevel;

    @Column(name = "classroom_mode", nullable = false)
    private String classroomMode;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "source_task_id")
    private UUID sourceTaskId;

    @Column(name = "source_task_config_version")
    private Integer sourceTaskConfigVersion;

    @Column(name = "source_template_id")
    private UUID sourceTemplateId;

    @Column(name = "source_publication_id")
    private UUID sourcePublicationId;

    @Column(name = "config_snapshot", nullable = false, columnDefinition = "jsonb")
    private String configSnapshot;

    @Column(nullable = false)
    private String status;

    @Column(name = "archived_at")
    private Instant archivedAt;

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

    public UUID getSchoolId() {
        return schoolId;
    }

    public void setSchoolId(UUID schoolId) {
        this.schoolId = schoolId;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public void setOwnerAccountId(UUID ownerAccountId) {
        this.ownerAccountId = ownerAccountId;
    }

    public String getOwnerRole() {
        return ownerRole;
    }

    public void setOwnerRole(String ownerRole) {
        this.ownerRole = ownerRole;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public UUID getSourceTaskId() {
        return sourceTaskId;
    }

    public void setSourceTaskId(UUID sourceTaskId) {
        this.sourceTaskId = sourceTaskId;
    }

    public Integer getSourceTaskConfigVersion() {
        return sourceTaskConfigVersion;
    }

    public void setSourceTaskConfigVersion(Integer sourceTaskConfigVersion) {
        this.sourceTaskConfigVersion = sourceTaskConfigVersion;
    }

    public UUID getSourceTemplateId() {
        return sourceTemplateId;
    }

    public void setSourceTemplateId(UUID sourceTemplateId) {
        this.sourceTemplateId = sourceTemplateId;
    }

    public UUID getSourcePublicationId() {
        return sourcePublicationId;
    }

    public void setSourcePublicationId(UUID sourcePublicationId) {
        this.sourcePublicationId = sourcePublicationId;
    }

    public String getConfigSnapshot() {
        return configSnapshot;
    }

    public void setConfigSnapshot(String configSnapshot) {
        this.configSnapshot = configSnapshot;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
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
