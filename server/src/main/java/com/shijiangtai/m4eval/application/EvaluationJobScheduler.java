package com.shijiangtai.m4eval.application;

import com.shijiangtai.m4eval.application.port.AfterCommitPort;
import com.shijiangtai.m4eval.application.port.DimensionScoreRepository;
import com.shijiangtai.m4eval.application.port.EvaluationJobRepository;
import com.shijiangtai.m4eval.application.port.EvaluationQueue;
import com.shijiangtai.m4eval.application.port.EvaluationRepository;
import com.shijiangtai.m4eval.domain.DimensionScore;
import com.shijiangtai.m4eval.domain.DimensionCatalog;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationJob;
import com.shijiangtai.m4eval.domain.EvaluationJobStatus;
import com.shijiangtai.m4eval.domain.EvaluationOutcome;
import com.shijiangtai.m4eval.domain.EvaluationStatus;
import com.shijiangtai.m4eval.domain.EvaluationTerminalEvent;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.EventPublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispatch, retry and dead-letter (06 §5.4, 05 §4.4).
 *
 * <p>Backoff is 15 / 60 / 180 seconds and at most 3 deliveries. A stale running job means the
 * worker vanished; it is re-published with the next attempt.
 */
@Component
public class EvaluationJobScheduler {

    public static final int MAX_ATTEMPTS = 3;
    public static final long STALE_RUNNING_SECONDS = 10 * 60;

    private static final Logger log = LoggerFactory.getLogger(EvaluationJobScheduler.class);
    private static final int BATCH = 50;

    private final EvaluationRepository evaluationRepository;
    private final EvaluationJobRepository jobRepository;
    private final DimensionScoreRepository dimensionScoreRepository;
    private final EvaluationQueue queue;
    private final AfterCommitPort afterCommit;
    private final EventPublisher eventPublisher;
    private final ClockProvider clock;

    public EvaluationJobScheduler(
            EvaluationRepository evaluationRepository,
            EvaluationJobRepository jobRepository,
            DimensionScoreRepository dimensionScoreRepository,
            EvaluationQueue queue,
            AfterCommitPort afterCommit,
            EventPublisher eventPublisher,
            ClockProvider clock) {
        this.evaluationRepository = Objects.requireNonNull(
                evaluationRepository, "evaluationRepository");
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.dimensionScoreRepository = Objects.requireNonNull(
                dimensionScoreRepository, "dimensionScoreRepository");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.afterCommit = Objects.requireNonNull(afterCommit, "afterCommit");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(fixedDelayString = "${m4.eval.scheduler-interval-ms:10000}",
            initialDelayString = "${m4.eval.scheduler-initial-delay-ms:15000}")
    @Transactional
    public void dispatchQueued() {
        Instant now = clock.now();
        for (EvaluationJob job : jobRepository.findDispatchable(now, BATCH)) {
            Evaluation evaluation = evaluationRepository.findById(job.evaluationId())
                    .orElse(null);
            if (evaluation == null) {
                job.fail("evaluation row missing", now);
                jobRepository.save(job);
                continue;
            }
            if (evaluation.terminal()) {
                job.markTerminal(jobStatus(evaluation.status()),
                        evaluation.errorMessage(), now);
                jobRepository.save(job);
                continue;
            }
            int nextAttempt = job.attempts() + 1;
            if (nextAttempt > MAX_ATTEMPTS) {
                deadLetterInternal(job, evaluation, "retry exhausted", now);
                continue;
            }
            job.requeue(nextAttempt, now.plusSeconds(backoffSeconds(nextAttempt)), now);
            EvaluationQueue.EvaluationDispatchMessage message = message(evaluation, job, now);
            jobRepository.save(job);
            afterCommit.afterCommit(() -> publishQuietly(message));
        }
    }

    @Scheduled(fixedDelayString = "${m4.eval.scheduler-interval-ms:10000}",
            initialDelayString = "${m4.eval.scheduler-initial-delay-ms:20000}")
    @Transactional
    public void requeueStale() {
        Instant now = clock.now();
        Instant staleBefore = now.minusSeconds(STALE_RUNNING_SECONDS);
        for (EvaluationJob job : jobRepository.findStaleRunning(staleBefore, BATCH)) {
            Evaluation evaluation = evaluationRepository.findById(job.evaluationId())
                    .orElse(null);
            if (evaluation == null || evaluation.terminal()) {
                job.markTerminal(
                        evaluation == null
                                ? EvaluationJobStatus.FAILED
                                : jobStatus(evaluation.status()),
                        "evaluation missing or already terminal",
                        now);
                jobRepository.save(job);
                continue;
            }
            int nextAttempt = job.attempts() + 1;
            if (nextAttempt > MAX_ATTEMPTS) {
                deadLetterInternal(job, evaluation, "worker did not report in time", now);
                continue;
            }
            // The message is published immediately; the retry timestamp only guards a lost XADD.
            job.requeue(nextAttempt, now.plusSeconds(backoffSeconds(nextAttempt)), now);
            jobRepository.save(job);
            EvaluationQueue.EvaluationDispatchMessage message =
                    message(evaluation, job, now);
            afterCommit.afterCommit(() -> publishQuietly(message));
            log.warn("re-published stale evaluation job {} as attempt {}",
                    job.id(), nextAttempt);
        }
    }

    @Transactional
    public void deadLetter(UUID jobId, String reason) {
        Instant now = clock.now();
        EvaluationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.terminal()) {
            return;
        }
        Evaluation evaluation = evaluationRepository.findById(job.evaluationId()).orElse(null);
        if (evaluation == null) {
            job.fail(reason, now);
            jobRepository.save(job);
            return;
        }
        deadLetterInternal(job, evaluation, reason, now);
    }

    private void deadLetterInternal(
            EvaluationJob job, Evaluation evaluation, String reason, Instant now) {
        if (evaluation.terminal()) {
            job.markTerminal(jobStatus(evaluation.status()), reason, now);
            jobRepository.save(job);
            return;
        }
        List<DimensionScore> existing = dimensionScoreRepository.findByEvaluationId(
                evaluation.id());
        if (existing.isEmpty()) {
            evaluation.fail(EvaluationErrorCode.EVALUATION_TIMEOUT.name(), reason, now);
            job.markTerminal(EvaluationJobStatus.FAILED, reason, now);
        } else {
            evaluation.apply(new EvaluationOutcome(
                    EvaluationStatus.PARTIAL,
                    null,
                    evaluation.warnings(),
                    List.of("DIMENSION_TIMEOUT"),
                    null,
                    null,
                    DimensionCatalog.RUBRIC_VERSION,
                    Map.of(),
                    now));
            job.markTerminal(EvaluationJobStatus.PARTIAL, reason, now);
        }
        evaluationRepository.save(evaluation);
        jobRepository.save(job);

        EvaluationTerminalEvent event = new EvaluationTerminalEvent(
                evaluation.id(),
                evaluation.sourceType(),
                evaluation.sourceId(),
                evaluation.sessionId(),
                evaluation.status(),
                evaluation.completedAt(),
                job.attempts());
        EvaluationQueue.EvaluationDispatchMessage message = message(evaluation, job, now);
        afterCommit.afterCommit(() -> {
            eventPublisher.publish(event);
            queue.deadLetter(message, reason);
        });
    }

    private void publishQuietly(EvaluationQueue.EvaluationDispatchMessage message) {
        try {
            queue.publish(message);
        } catch (RuntimeException exception) {
            log.warn("publish evaluation job {} failed: {}",
                    message.jobId(), exception.getMessage());
        }
    }

    private static EvaluationQueue.EvaluationDispatchMessage message(
            Evaluation evaluation, EvaluationJob job, Instant now) {
        return new EvaluationQueue.EvaluationDispatchMessage(
                job.id(),
                evaluation.id(),
                evaluation.sourceType(),
                evaluation.sourceId(),
                evaluation.sessionId(),
                job.attempts(),
                now);
    }

    static long backoffSeconds(int attempt) {
        return switch (attempt) {
            case 1 -> 15;
            case 2 -> 60;
            default -> 180;
        };
    }

    static EvaluationJobStatus jobStatus(EvaluationStatus status) {
        return switch (status) {
            case COMPLETED -> EvaluationJobStatus.COMPLETED;
            case PARTIAL -> EvaluationJobStatus.PARTIAL;
            case FAILED -> EvaluationJobStatus.FAILED;
            default -> EvaluationJobStatus.QUEUED;
        };
    }
}
