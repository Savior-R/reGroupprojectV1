package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tasks", schema = "m3_classroom")
public class TaskEntity {
    @Id
    private UUID id;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(nullable = false, length = 16)
    private String mode;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(name = "textbook_version", length = 100)
    private String textbookVersion;

    @Column(name = "reference_material", columnDefinition = "jsonb")
    private String referenceMaterial;

    @Column(name = "scenario_config", columnDefinition = "jsonb")
    private String scenarioConfig;

    @Column(name = "duration_recommended_seconds", nullable = false)
    private int durationRecommendedSeconds;

    @Column(name = "duration_max_seconds", nullable = false)
    private int durationMaxSeconds;

    @Column(name = "rubric_weights", nullable = false, columnDefinition = "jsonb")
    private String rubricWeights;

    @Column(name = "question_config", columnDefinition = "jsonb")
    private String questionConfig;

    @Column(name = "config_snapshot", columnDefinition = "jsonb")
    private String configSnapshot;

    @Column(name = "config_version", nullable = false)
    private int configVersion;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

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

    public UUID getCourseId() {
        return courseId;
    }

    public void setCourseId(UUID courseId) {
        this.courseId = courseId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getTextbookVersion() {
        return textbookVersion;
    }

    public void setTextbookVersion(String textbookVersion) {
        this.textbookVersion = textbookVersion;
    }

    public String getReferenceMaterial() {
        return referenceMaterial;
    }

    public void setReferenceMaterial(String referenceMaterial) {
        this.referenceMaterial = referenceMaterial;
    }

    public String getScenarioConfig() {
        return scenarioConfig;
    }

    public void setScenarioConfig(String scenarioConfig) {
        this.scenarioConfig = scenarioConfig;
    }

    public int getDurationRecommendedSeconds() {
        return durationRecommendedSeconds;
    }

    public void setDurationRecommendedSeconds(int durationRecommendedSeconds) {
        this.durationRecommendedSeconds = durationRecommendedSeconds;
    }

    public int getDurationMaxSeconds() {
        return durationMaxSeconds;
    }

    public void setDurationMaxSeconds(int durationMaxSeconds) {
        this.durationMaxSeconds = durationMaxSeconds;
    }

    public String getRubricWeights() {
        return rubricWeights;
    }

    public void setRubricWeights(String rubricWeights) {
        this.rubricWeights = rubricWeights;
    }

    public String getQuestionConfig() {
        return questionConfig;
    }

    public void setQuestionConfig(String questionConfig) {
        this.questionConfig = questionConfig;
    }

    public String getConfigSnapshot() {
        return configSnapshot;
    }

    public void setConfigSnapshot(String configSnapshot) {
        this.configSnapshot = configSnapshot;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
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
