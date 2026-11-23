package com.shijiangtai.m4eval.application;

import com.shijiangtai.m4eval.application.port.AfterCommitPort;
import com.shijiangtai.m4eval.application.port.EvaluationInputQuery;
import com.shijiangtai.m4eval.application.port.EvaluationInputSnapshotRepository;
import com.shijiangtai.m4eval.application.port.EvaluationJobRepository;
import com.shijiangtai.m4eval.application.port.EvaluationQueue;
import com.shijiangtai.m4eval.application.port.EvaluationRepository;
import com.shijiangtai.m4eval.application.view.EvaluationView;
import com.shijiangtai.m4eval.domain.DimensionCatalog;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import com.shijiangtai.m4eval.domain.EvaluationJob;
import com.shijiangtai.m4eval.domain.EvaluationMode;
import com.shijiangtai.m4eval.domain.InputSnapshot;
import com.shijiangtai.m4eval.domain.WeightDefaults;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and reads evaluations (05 §4.1). Creation is idempotent on
 * {@code (source_type, source_id)}; the database unique key is the final guard.
 */
@Service
public class EvaluationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationApplicationService.class);
    private static final long INITIAL_RETRY_SECONDS = 15;

    private final EvaluationRepository evaluationRepository;
    private final EvaluationJobRepository jobRepository;
    private final EvaluationInputSnapshotRepository snapshotRepository;
    private final EvaluationInputQuery inputQuery;
    private final EvaluationQueue queue;
    private final AfterCommitPort afterCommit;
    private final EvaluationAccessPolicy accessPolicy;
    private final IdGenerator idGenerator;
    private final ClockProvider clock;

    public EvaluationApplicationService(
            EvaluationRepository evaluationRepository,
            EvaluationJobRepository jobRepository,
            EvaluationInputSnapshotRepository snapshotRepository,
            EvaluationInputQuery inputQuery,
            EvaluationQueue queue,
            AfterCommitPort afterCommit,
            EvaluationAccessPolicy accessPolicy,
            IdGenerator idGenerator,
            ClockProvider clock) {
        this.evaluationRepository = Objects.requireNonNull(
                evaluationRepository, "evaluationRepository");
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.snapshotRepository = Objects.requireNonNull(
                snapshotRepository, "snapshotRepository");
        this.inputQuery = Objects.requireNonNull(inputQuery, "inputQuery");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.afterCommit = Objects.requireNonNull(afterCommit, "afterCommit");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Trigger entry point; duplicate events return the existing evaluation and job. */
    @Transactional
    public Evaluation getOrCreateForSubmission(UUID submissionId) {
        return evaluationRepository
                .findBySource(Evaluation.SOURCE_TASK_SUBMISSION, submissionId)
                .orElseGet(() -> createForSubmission(submissionId));
    }

    @Transactional
    public Evaluation getOrCreateForTrainingRecord(UUID trainingRecordId) {
        return evaluationRepository
                .findBySource(Evaluation.SOURCE_TRAINING_RECORD, trainingRecordId)
                .orElseGet(() -> createForTrainingRecord(trainingRecordId));
    }

    private Evaluation createForSubmission(UUID submissionId) {
        EvaluationInputQuery.SubmissionContext submission = inputQuery
                .findSubmission(submissionId)
                .orElseThrow(() -> notFound("提交件不存在"));
        EvaluationInputQuery.SessionInfo session = inputQuery
                .findSession(submission.sessionId())
                .orElseThrow(() -> notFound("会话不存在"));

        EvaluationMode mode = EvaluationMode.fromWire(submission.mode());
        Map<String, Integer> weights = WeightDefaults.normalize(mode, submission.weights());
        Instant now = clock.now();

        boolean transcript = inputQuery.countTexts(session.sessionId()) > 0;
        boolean audio = hasUploadedStream(session.sessionId(), "audio");
        boolean frames = inputQuery.countFrames(session.sessionId()) > 0;
        boolean events = inputQuery.countEvents(session.sessionId()) > 0;
        boolean reference = submission.referenceMaterial() != null
                && !submission.referenceMaterial().isEmpty();
        boolean answerPoints = !mode.equals(EvaluationMode.INTERVIEW)
                || hasAnswerPoints(submission.questionConfig());

        Evaluation evaluation = Evaluation.pending(
                idGenerator.nextId(),
                Evaluation.SOURCE_TASK_SUBMISSION,
                submissionId,
                submissionId,
                null,
                session.sessionId(),
                submission.taskId(),
                submission.courseId(),
                submission.studentAccountId(),
                mode,
                weights,
                submission.taskConfigVersion(),
                null,
                now);
        return persistAndDispatch(
                evaluation,
                InputFlags.compute(transcript, audio, frames, events, reference, answerPoints),
                now);
    }

    private Evaluation createForTrainingRecord(UUID trainingRecordId) {
        EvaluationInputQuery.TrainingContext training = inputQuery
                .findTrainingRecord(trainingRecordId)
                .orElseThrow(() -> notFound("训练记录不存在"));
        EvaluationInputQuery.SessionInfo session = inputQuery
                .findSession(training.sessionId())
                .orElseThrow(() -> notFound("会话不存在"));

        EvaluationMode mode = EvaluationMode.fromWire(training.mode());
        Map<String, Integer> weights = WeightDefaults.normalize(mode, training.weights());
        Instant now = clock.now();

        boolean transcript = inputQuery.countTexts(session.sessionId()) > 0;
        boolean audio = hasUploadedStream(session.sessionId(), "audio");
        boolean frames = inputQuery.countFrames(session.sessionId()) > 0;
        boolean events = inputQuery.countEvents(session.sessionId()) > 0;
        Object reference = training.configSnapshot() == null
                ? null : training.configSnapshot().get("reference_material");
        boolean referenceComplete = reference instanceof String text && !text.isBlank();
        boolean answerPoints = !mode.equals(EvaluationMode.INTERVIEW)
                || hasAnswerPoints(training.configSnapshot());

        Evaluation evaluation = Evaluation.pending(
                idGenerator.nextId(),
                Evaluation.SOURCE_TRAINING_RECORD,
                trainingRecordId,
                null,
                trainingRecordId,
                session.sessionId(),
                null,
                training.courseId(),
                training.studentAccountId(),
                mode,
                weights,
                null,
                training.trainingConfigVersion(),
                now);
        return persistAndDispatch(
                evaluation,
                InputFlags.compute(
                        transcript, audio, frames, events, referenceComplete, answerPoints),
                now);
    }

    private Evaluation persistAndDispatch(
            Evaluation evaluation, Map<String, Object> flags, Instant now) {
        Evaluation saved = evaluationRepository.save(evaluation);

        EvaluationJob job = EvaluationJob.queue(
                idGenerator.nextId(),
                saved.id(),
                now.plusSeconds(INITIAL_RETRY_SECONDS),
                now);
        jobRepository.save(job);

        // config_version follows the source that produced the evaluation snapshot.
        int configVersion = saved.submissionId() != null
                ? (saved.taskConfigVersion() == null ? 0 : saved.taskConfigVersion())
                : (saved.trainingConfigVersion() == null ? 0 : saved.trainingConfigVersion());
        snapshotRepository.save(new InputSnapshot(
                saved.id(),
                saved.sourceType(),
                saved.sourceId(),
                saved.submissionId(),
                saved.trainingRecordId(),
                inputQuery.recordingStreamIds(saved.sessionId()),
                inputQuery.textIds(saved.sessionId()),
                inputQuery.frameIds(saved.sessionId()),
                inputQuery.eventIds(saved.sessionId()),
                configVersion,
                saved.taskConfigVersion(),
                saved.trainingConfigVersion(),
                flags,
                now));

        EvaluationQueue.EvaluationDispatchMessage message = message(saved, job, now);
        afterCommit.afterCommit(() -> publishQuietly(message));
        return saved;
    }

    private void publishQuietly(EvaluationQueue.EvaluationDispatchMessage message) {
        try {
            queue.publish(message);
        } catch (RuntimeException exception) {
            log.warn("evaluation dispatch failed for job {}, scheduler will retry: {}",
                    message.jobId(), exception.getMessage());
        }
    }

    private static EvaluationQueue.EvaluationDispatchMessage message(
            Evaluation evaluation, EvaluationJob job, Instant now) {
        // Use the injected clock so the queue message is deterministic and reproducible.
        return new EvaluationQueue.EvaluationDispatchMessage(
                job.id(),
                evaluation.id(),
                evaluation.sourceType(),
                evaluation.sourceId(),
                evaluation.sessionId(),
                job.attempts(),
                now);
    }

    public EvaluationView get(CurrentUser user, UUID evaluationId) {
        Evaluation evaluation = evaluationRepository
                .findById(evaluationId)
                .orElseThrow(() -> notFound("评测不存在"));
        accessPolicy.requireRead(user, evaluation);
        return EvaluationView.from(evaluation);
    }

    public EvaluationView getBySubmission(CurrentUser user, UUID submissionId) {
        Evaluation evaluation = evaluationRepository
                .findBySource(Evaluation.SOURCE_TASK_SUBMISSION, submissionId)
                .orElseThrow(() -> notFound("评测不存在"));
        accessPolicy.requireRead(user, evaluation);
        return EvaluationView.from(evaluation);
    }

    public EvaluationView getByTrainingRecord(CurrentUser user, UUID trainingRecordId) {
        Evaluation evaluation = evaluationRepository
                .findBySource(Evaluation.SOURCE_TRAINING_RECORD, trainingRecordId)
                .orElseThrow(() -> notFound("评测不存在"));
        accessPolicy.requireRead(user, evaluation);
        return EvaluationView.from(evaluation);
    }

    public Optional<Evaluation> find(UUID evaluationId) {
        return evaluationRepository.findById(evaluationId);
    }

    private boolean hasUploadedStream(UUID sessionId, String streamType) {
        return inputQuery.recordingStreams(sessionId).stream()
                .anyMatch(stream -> streamType.equals(stream.streamType())
                        && "uploaded".equals(stream.status()));
    }

    @SuppressWarnings("unchecked")
    private static boolean hasAnswerPoints(Map<String, Object> questionConfig) {
        if (questionConfig == null || questionConfig.isEmpty()) {
            return false;
        }
        Object questions = questionConfig.get("questions");
        if (questions instanceof List<?> list) {
            return !list.isEmpty() && list.stream().allMatch(item -> {
                if (item instanceof Map<?, ?> map) {
                    Object points = map.get("answer_points");
                    return points != null && !String.valueOf(points).isBlank();
                }
                return false;
            });
        }
        Object points = questionConfig.get("answer_points");
        return points != null && !String.valueOf(points).isBlank();
    }

    private static EvaluationException notFound(String message) {
        return new EvaluationException(EvaluationErrorCode.EVALUATION_NOT_FOUND, message);
    }

    /** Rubric version is fixed for this release; exposed for manifest assembly. */
    public static String rubricVersion() {
        return DimensionCatalog.RUBRIC_VERSION;
    }
}
