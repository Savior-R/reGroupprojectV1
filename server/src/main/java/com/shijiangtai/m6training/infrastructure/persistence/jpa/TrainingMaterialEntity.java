package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "training_materials", schema = "m6_training")
public class TrainingMaterialEntity {
    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false)
    private UUID ownerAccountId;

    @Column(nullable = false)
    private String usage;

    @Column(name = "template_id")
    private UUID templateId;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String mime;

    @Column(nullable = false)
    private long size;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "parsed_text")
    private String parsedText;

    @Column(name = "char_count")
    private Integer charCount;

    @Column(nullable = false)
    private String status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public void setOwnerAccountId(UUID ownerAccountId) {
        this.ownerAccountId = ownerAccountId;
    }

    public String getUsage() {
        return usage;
    }

    public void setUsage(String usage) {
        this.usage = usage;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getMime() {
        return mime;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getParsedText() {
        return parsedText;
    }

    public void setParsedText(String parsedText) {
        this.parsedText = parsedText;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
