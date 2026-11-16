package com.shijiangtai.m4eval.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Evaluation aggregate (06 §3; {@code m4.evaluations}).
 *
 * <p>Java is the state authority: status, weights, total score and attempt count are decided
 * here. A retry updates the same evaluation and keeps the same {@code evaluation_id}.
 */
public final class Evaluation {

    public static final String SOURCE_TASK_SUBMISSION = "task_submission";
    public static final String SOURCE_TRAINING_RECORD = "training_record";

    private final UUID id;
    private final String sourceType;
    private final UUID sourceId;
    private final UUID submissionId;
    private final UUID trainingRecordId;
    private final UUID sessionId;
    private final UUID taskId;
    private final UUID courseId;
    private final UUID studentAccountId;
    private final EvaluationMode mode;
    private final Map<String, Integer> weights;
    private final Integer taskConfigVersion;
    private final Integer trainingConfigVersion;
    private final Instant createdAt;
    private EvaluationStatus status;
    private BigDecimal totalScore;
    private String modelVersion;
    private String promptVersion;
    private String rubricVersion;
    private int attemptCount;
    private List<String> warnings;
    private List<String> partialReasons;
    private String errorCode;
    private String errorMessage;
    private Map<String, Object> tokenUsage;
    private Instant startedAt;
    private Instant completedAt;
    private Instant updatedAt;

    private Evaluation(
            UUID id,
            String sourceType,
            UUID sourceId,
            UUID submissionId,
            UUID trainingRecordId,
            UUID sessionId,
            UUID taskId,
            UUID courseId,
            UUID studentAccountId,
            EvaluationMode mode,
            EvaluationStatus status,
            BigDecimal totalScore,
            Map<String, Integer> weights,
            Integer taskConfigVersion,
            Integer trainingConfigVersion,
            String modelVersion,
            String promptVersion,
            String rubricVersion,
            int attemptCount,
            List<String> warnings,
            List<String> partialReasons,
            String errorCode,
            String errorMessage,
            Map<String, Object> tokenUsage,
            Instant startedAt,
            Instant createdAt,
            Instant completedAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.submissionId = submissionId;
        this.trainingRecordId = trainingRecordId;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.taskId = taskId;
        this.courseId = courseId;
        this.studentAccountId = Objects.requireNonNull(studentAccountId, "studentAccountId");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.weights = Collections.unmodifiableMap(
                new LinkedHashMap<>(weights == null ? Map.of() : weights));
        this.taskConfigVersion = taskConfigVersion;
        this.trainingConfigVersion = trainingConfigVersion;
        this.status = Objects.requireNonNull(status, "status");
        this.totalScore = totalScore;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.rubricVersion = rubricVersion;
        this.attemptCount = attemptCount;
        this.warnings = new ArrayList<>(warnings == null ? List.of() : warnings);
        this.partialReasons = new ArrayList<>(
                partialReasons == null ? List.of() : partialReasons);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.tokenUsage = tokenUsage == null ? Map.of() : Map.copyOf(tokenUsage);
        this.startedAt = startedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.completedAt = completedAt;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if ((SOURCE_TASK_SUBMISSION.equals(sourceType)) != (submissionId != null)) {
            throw new IllegalArgumentException("task_submission requires submissionId only");
        }
        if ((SOURCE_TRAINING_RECORD.equals(sourceType)) != (trainingRecordId != null)) {
            throw new IllegalArgumentException("training_record requires trainingRecordId only");
        }
        if (status == EvaluationStatus.COMPLETED && totalScore == null) {
            throw new IllegalArgumentException("completed evaluation requires totalScore");
        }
        if (status != EvaluationStatus.COMPLETED && totalScore != null) {
            throw new IllegalArgumentException("only completed evaluation carries totalScore");
        }
    }

    public static Evaluation pending(
            UUID id,
            String sourceType,
            UUID sourceId,
            UUID submissionId,
            UUID trainingRecordId,
            UUID sessionId,
            UUID taskId,
            UUID courseId,
            UUID studentAccountId,
            EvaluationMode mode,
            Map<String, Integer> weights,
            Integer taskConfigVersion,
            Integer trainingConfigVersion,
            Instant now) {
        return new Evaluation(
                id, sourceType, sourceId, submissionId, trainingRecordId, sessionId, taskId,
                courseId, studentAccountId, mode, EvaluationStatus.PENDING, null, weights,
                taskConfigVersion, trainingConfigVersion, null, null, DimensionCatalog.RUBRIC_VERSION,
                0, List.of(), List.of(), null, null, Map.of(), null, now, null, now);
    }

    public static Evaluation rehydrate(
            UUID id,
            String sourceType,
            UUID sourceId,
            UUID submissionId,
            UUID trainingRecordId,
            UUID sessionId,
            UUID taskId,
            UUID courseId,
            UUID studentAccountId,
            EvaluationMode mode,
            EvaluationStatus status,
            BigDecimal totalScore,
            Map<String, Integer> weights,
            Integer taskConfigVersion,
            Integer trainingConfigVersion,
            String modelVersion,
            String promptVersion,
            String rubricVersion,
            int attemptCount,
            List<String> warnings,
            List<String> partialReasons,
            String errorCode,
            String errorMessage,
            Map<String, Object> tokenUsage,
            Instant startedAt,
            Instant createdAt,
            Instant completedAt,
            Instant updatedAt) {
        return new Evaluation(
                id, sourceType, sourceId, submissionId, trainingRecordId, sessionId, taskId,
                courseId, studentAccountId, mode, status, totalScore, weights, taskConfigVersion,
                trainingConfigVersion, modelVersion, promptVersion, rubricVersion, attemptCount,
                warnings, partialReasons, errorCode, errorMessage, tokenUsage, startedAt,
                createdAt, completedAt, updatedAt);
    }

    /** Idempotent start: a terminal evaluation is never moved back to running. */
    public void markRunning(int attempt, Instant now) {
        if (status.terminal()) {
            return;
        }
        this.status = EvaluationStatus.RUNNING;
        this.attemptCount = Math.max(attemptCount, attempt);
        if (startedAt == null) {
            this.startedAt = now;
        }
        this.updatedAt = now;
    }

    public void recordAttempt(int attempt, Instant now) {
        this.attemptCount = Math.max(attemptCount, attempt);
        this.updatedAt = now;
    }

    public void apply(EvaluationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (status.terminal()) {
            return;
        }
        this.status = outcome.status();
        this.totalScore = outcome.totalScore();
        this.warnings = new ArrayList<>(outcome.warnings());
        this.partialReasons = new ArrayList<>(outcome.partialReasons());
        this.modelVersion = outcome.modelVersion();
        this.promptVersion = outcome.promptVersion();
        this.rubricVersion = outcome.rubricVersion();
        this.tokenUsage = Map.copyOf(outcome.tokenUsage());
        this.completedAt = outcome.completedAt();
        this.errorCode = null;
        this.errorMessage = null;
        this.updatedAt = outcome.completedAt();
        if (startedAt == null) {
            this.startedAt = outcome.completedAt();
        }
    }

    public void fail(String errorCode, String errorMessage, Instant now) {
        if (status.terminal()) {
            return;
        }
        this.status = EvaluationStatus.FAILED;
        this.totalScore = null;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.completedAt = now;
        this.updatedAt = now;
        if (startedAt == null) {
            this.startedAt = now;
        }
    }

    public boolean terminal() {
        return status.terminal();
    }

    public UUID id() {
        return id;
    }

    public String sourceType() {
        return sourceType;
    }

    public UUID sourceId() {
        return sourceId;
    }

    public UUID submissionId() {
        return submissionId;
    }

    public UUID trainingRecordId() {
        return trainingRecordId;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID taskId() {
        return taskId;
    }

    public UUID courseId() {
        return courseId;
    }

    public UUID studentAccountId() {
        return studentAccountId;
    }

    public EvaluationMode mode() {
        return mode;
    }

    public EvaluationStatus status() {
        return status;
    }

    public BigDecimal totalScore() {
        return totalScore;
    }

    public Map<String, Integer> weights() {
        return weights;
    }

    public Integer taskConfigVersion() {
        return taskConfigVersion;
    }

    public Integer trainingConfigVersion() {
        return trainingConfigVersion;
    }

    public String modelVersion() {
        return modelVersion;
    }

    public String promptVersion() {
        return promptVersion;
    }

    public String rubricVersion() {
        return rubricVersion;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public List<String> partialReasons() {
        return List.copyOf(partialReasons);
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Map<String, Object> tokenUsage() {
        return tokenUsage;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
