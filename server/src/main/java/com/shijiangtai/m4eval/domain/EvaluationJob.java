package com.shijiangtai.m4eval.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Java-side queue task (06 §5.1, {@code m4.evaluation_jobs}). */
public final class EvaluationJob {

    private final UUID id;
    private final UUID evaluationId;
    private final Instant createdAt;
    private EvaluationJobStatus status;
    private int attempts;
    private String lastError;
    private Instant nextRetryAt;
    private Instant updatedAt;

    private EvaluationJob(
            UUID id,
            UUID evaluationId,
            EvaluationJobStatus status,
            int attempts,
            String lastError,
            Instant nextRetryAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        this.status = Objects.requireNonNull(status, "status");
        this.attempts = attempts;
        this.lastError = lastError;
        this.nextRetryAt = nextRetryAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    /**
     * Initial job: attempt 1 is published immediately; the retry timestamp only matters when the
     * first {@code XADD} is lost, so the scheduler picks it up after the first backoff window.
     */
    public static EvaluationJob queue(
            UUID id, UUID evaluationId, Instant nextRetryAt, Instant now) {
        return new EvaluationJob(
                id, evaluationId, EvaluationJobStatus.QUEUED, 1, null, nextRetryAt, now, now);
    }

    public static EvaluationJob rehydrate(
            UUID id,
            UUID evaluationId,
            EvaluationJobStatus status,
            int attempts,
            String lastError,
            Instant nextRetryAt,
            Instant createdAt,
            Instant updatedAt) {
        return new EvaluationJob(
                id, evaluationId, status, attempts, lastError, nextRetryAt, createdAt, updatedAt);
    }

    public void markRunning(Instant now) {
        if (terminal()) {
            return;
        }
        this.status = EvaluationJobStatus.RUNNING;
        this.nextRetryAt = null;
        this.updatedAt = now;
    }

    public void markTerminal(EvaluationJobStatus terminal, String error, Instant now) {
        if (!terminal.terminal()) {
            throw new IllegalArgumentException("terminal status required");
        }
        this.status = terminal;
        this.lastError = error;
        this.nextRetryAt = null;
        this.updatedAt = now;
    }

    /** Schedules another delivery; the caller decides whether {@code attempts} is still legal. */
    public void requeue(int nextAttempt, Instant nextRetryAt, Instant now) {
        this.status = EvaluationJobStatus.QUEUED;
        this.attempts = nextAttempt;
        this.nextRetryAt = nextRetryAt;
        this.updatedAt = now;
    }

    public void fail(String error, Instant now) {
        this.status = EvaluationJobStatus.FAILED;
        this.lastError = error;
        this.nextRetryAt = null;
        this.updatedAt = now;
    }

    public boolean terminal() {
        return status.terminal();
    }

    public UUID id() {
        return id;
    }

    public UUID evaluationId() {
        return evaluationId;
    }

    public EvaluationJobStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public String lastError() {
        return lastError;
    }

    public Instant nextRetryAt() {
        return nextRetryAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
