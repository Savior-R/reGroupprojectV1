package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "virtual_students", schema = "m3_classroom")
public class VirtualStudentEntity {
    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(nullable = false, length = 64)
    private String avatar;

    @Column(name = "personality_text", nullable = false, length = 500)
    private String personalityText;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal activity;

    @Column(name = "question_rate", nullable = false, precision = 3, scale = 2)
    private BigDecimal questionRate;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal ability;

    @Column(name = "attention_baseline", nullable = false, precision = 3, scale = 2)
    private BigDecimal attentionBaseline;

    @Column(name = "discipline_probability", nullable = false, precision = 3, scale = 2)
    private BigDecimal disciplineProbability;

    @Column(name = "seat_no", nullable = false)
    private int seatNo;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getPersonalityText() {
        return personalityText;
    }

    public void setPersonalityText(String personalityText) {
        this.personalityText = personalityText;
    }

    public BigDecimal getActivity() {
        return activity;
    }

    public void setActivity(BigDecimal activity) {
        this.activity = activity;
    }

    public BigDecimal getQuestionRate() {
        return questionRate;
    }

    public void setQuestionRate(BigDecimal questionRate) {
        this.questionRate = questionRate;
    }

    public BigDecimal getAbility() {
        return ability;
    }

    public void setAbility(BigDecimal ability) {
        this.ability = ability;
    }

    public BigDecimal getAttentionBaseline() {
        return attentionBaseline;
    }

    public void setAttentionBaseline(BigDecimal attentionBaseline) {
        this.attentionBaseline = attentionBaseline;
    }

    public BigDecimal getDisciplineProbability() {
        return disciplineProbability;
    }

    public void setDisciplineProbability(BigDecimal disciplineProbability) {
        this.disciplineProbability = disciplineProbability;
    }

    public int getSeatNo() {
        return seatNo;
    }

    public void setSeatNo(int seatNo) {
        this.seatNo = seatNo;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
