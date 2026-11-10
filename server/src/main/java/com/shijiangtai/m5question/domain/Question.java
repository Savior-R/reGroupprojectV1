package com.shijiangtai.m5question.domain;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Question aggregate: stem, answer points, category, status and audit fields. */
public final class Question {
    public static final int MAX_STEM_LENGTH = 500;
    public static final int MAX_ANSWER_POINTS_LENGTH = 2000;

    private final UUID id;
    private final UUID schoolId;
    private final UUID createdBy;
    private final Instant createdAt;
    private UUID categoryId;
    private String stem;
    private String answerPoints;
    private QuestionStatus status;
    private UUID updatedBy;
    private Instant updatedAt;

    private Question(
            UUID id,
            UUID schoolId,
            UUID categoryId,
            String stem,
            String answerPoints,
            QuestionStatus status,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.schoolId = Objects.requireNonNull(schoolId, "schoolId");
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
        this.stem = requireStem(stem);
        this.answerPoints = requireAnswerPoints(answerPoints);
        this.status = Objects.requireNonNull(status, "status");
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Question create(
            UUID id,
            UUID schoolId,
            UUID categoryId,
            String stem,
            String answerPoints,
            UUID creator,
            Instant now) {
        Objects.requireNonNull(now, "now");
        return new Question(id, schoolId, categoryId, stem, answerPoints,
                QuestionStatus.ACTIVE, creator, creator, now, now);
    }

    public static Question rehydrate(
            UUID id,
            UUID schoolId,
            UUID categoryId,
            String stem,
            String answerPoints,
            QuestionStatus status,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt) {
        return new Question(id, schoolId, categoryId, stem, answerPoints, status,
                createdBy, updatedBy, createdAt, updatedAt);
    }

    public void update(
            UUID newCategoryId,
            String newStem,
            String newAnswerPoints,
            QuestionStatus newStatus,
            UUID updater,
            Instant now) {
        Objects.requireNonNull(now, "now");
        if (newCategoryId != null) {
            this.categoryId = newCategoryId;
        }
        if (newStem != null) {
            this.stem = requireStem(newStem);
        }
        if (newAnswerPoints != null) {
            this.answerPoints = requireAnswerPoints(newAnswerPoints);
        }
        if (newStatus != null) {
            this.status = newStatus;
        }
        this.updatedBy = updater;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == QuestionStatus.ACTIVE;
    }

    public boolean hasAnswerPoints() {
        return answerPoints != null && !answerPoints.isBlank();
    }

    public UUID id() {
        return id;
    }

    public UUID schoolId() {
        return schoolId;
    }

    public UUID categoryId() {
        return categoryId;
    }

    public String stem() {
        return stem;
    }

    public String answerPoints() {
        return answerPoints;
    }

    public QuestionStatus status() {
        return status;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private static String requireStem(String value) {
        if (value == null) {
            throw invalid("stem");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_STEM_LENGTH) {
            throw invalid("stem");
        }
        return trimmed;
    }

    private static String requireAnswerPoints(String value) {
        if (value == null) {
            throw new DomainException(ErrorCode.ANSWER_POINTS_MISSING,
                    "答案要点不能为空", Map.of("field", "answer_points"));
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new DomainException(ErrorCode.ANSWER_POINTS_MISSING,
                    "答案要点不能为空", Map.of("field", "answer_points"));
        }
        if (trimmed.length() > MAX_ANSWER_POINTS_LENGTH) {
            throw invalid("answer_points");
        }
        return trimmed;
    }

    private static DomainException invalid(String field) {
        return new DomainException(ErrorCode.VALIDATION_ERROR,
                "题目内容非法", Map.of("field", field));
    }
}
