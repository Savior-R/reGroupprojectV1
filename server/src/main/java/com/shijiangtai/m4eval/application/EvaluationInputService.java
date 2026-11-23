package com.shijiangtai.m4eval.application;

import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.m4eval.application.port.EvaluationInputQuery;
import com.shijiangtai.m4eval.application.port.EvaluationInputSnapshotRepository;
import com.shijiangtai.m4eval.application.port.EvaluationRepository;
import com.shijiangtai.m4eval.application.view.EvaluationInputManifestView;
import com.shijiangtai.m4eval.application.view.InputEventView;
import com.shijiangtai.m4eval.application.view.InputFrameView;
import com.shijiangtai.m4eval.application.view.InputTextView;
import com.shijiangtai.m4eval.domain.DimensionCatalog;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import com.shijiangtai.m4eval.domain.InputSnapshot;
import com.shijiangtai.shared.domain.PageResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal input read model consumed by the Python worker (06 §9.3).
 *
 * <p>Every list is bounded by the IDs captured in {@link InputSnapshot}, so a late write to M3
 * cannot change the evidence of an already-created evaluation.
 */
@Service
public class EvaluationInputService {

    private static final int MAX_TEXTS = 500;
    private static final int MAX_FRAMES = 200;
    private static final int MAX_EVENTS = 500;
    /** 06 §9.3: presigned recording URLs are valid for 15 minutes only. */
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final EvaluationRepository evaluationRepository;
    private final EvaluationInputSnapshotRepository snapshotRepository;
    private final EvaluationInputQuery inputQuery;
    private final RecordingObjectStorage objectStorage;

    public EvaluationInputService(
            EvaluationRepository evaluationRepository,
            EvaluationInputSnapshotRepository snapshotRepository,
            EvaluationInputQuery inputQuery,
            RecordingObjectStorage objectStorage) {
        this.evaluationRepository = Objects.requireNonNull(
                evaluationRepository, "evaluationRepository");
        this.snapshotRepository = Objects.requireNonNull(
                snapshotRepository, "snapshotRepository");
        this.inputQuery = Objects.requireNonNull(inputQuery, "inputQuery");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
    }

    @Transactional(readOnly = true)
    public EvaluationInputManifestView manifest(UUID evaluationId) {
        Evaluation evaluation = requireEvaluation(evaluationId);
        InputSnapshot snapshot = requireSnapshot(evaluationId);
        EvaluationInputQuery.SessionInfo session = inputQuery
                .findSession(evaluation.sessionId())
                .orElseThrow(() -> notFound("会话不存在"));

        Map<String, Object> config = configFor(evaluation);
        List<EvaluationInputManifestView.RecordingView> recordings = recordings(
                evaluation, snapshot);

        EvaluationInputManifestView.SessionView sessionView =
                new EvaluationInputManifestView.SessionView(
                        session.sessionType(),
                        session.status(),
                        session.durationMs(),
                        session.streamOffsets() == null ? Map.of() : session.streamOffsets(),
                        session.startedAt() == null ? null : session.startedAt().toString(),
                        session.endedAt() == null ? null : session.endedAt().toString());

        return new EvaluationInputManifestView(
                evaluation.id(),
                evaluation.sessionId(),
                evaluation.sourceType(),
                evaluation.sourceId(),
                evaluation.mode().wireValue(),
                sessionView,
                config,
                recordings,
                new EvaluationInputManifestView.CountsView(
                        inputQuery.countTexts(evaluation.sessionId()),
                        inputQuery.countFrames(evaluation.sessionId()),
                        inputQuery.countEvents(evaluation.sessionId())),
                evaluation.weights(),
                evaluation.rubricVersion() == null
                        ? DimensionCatalog.RUBRIC_VERSION : evaluation.rubricVersion(),
                snapshot.flags());
    }

    @Transactional(readOnly = true)
    public PageResult<InputTextView> texts(UUID evaluationId, UUID afterId, int limit) {
        Evaluation evaluation = requireEvaluation(evaluationId);
        int size = clamp(limit, MAX_TEXTS);
        List<InputTextView> rows = inputQuery
                .texts(evaluationId, evaluation.sessionId(), afterId, size)
                .stream()
                .map(InputTextView::from)
                .toList();
        return new PageResult<>(rows, rows.size(), 0, size);
    }

    @Transactional(readOnly = true)
    public PageResult<InputFrameView> frames(
            UUID evaluationId, Long afterTimestampMs, UUID afterId, int limit) {
        Evaluation evaluation = requireEvaluation(evaluationId);
        int size = clamp(limit, MAX_FRAMES);
        List<InputFrameView> rows = inputQuery
                .frames(evaluationId, evaluation.sessionId(), afterTimestampMs, afterId, size)
                .stream()
                .map(InputFrameView::from)
                .toList();
        return new PageResult<>(rows, rows.size(), 0, size);
    }

    @Transactional(readOnly = true)
    public PageResult<InputEventView> events(UUID evaluationId, Long afterSeq, int limit) {
        Evaluation evaluation = requireEvaluation(evaluationId);
        int size = clamp(limit, MAX_EVENTS);
        List<InputEventView> rows = inputQuery
                .events(evaluationId, evaluation.sessionId(), afterSeq, size)
                .stream()
                .map(InputEventView::from)
                .toList();
        return new PageResult<>(rows, rows.size(), 0, size);
    }

    private Map<String, Object> configFor(Evaluation evaluation) {
        LinkedHashMap<String, Object> config = new LinkedHashMap<>();
        config.put("weights", evaluation.weights());
        if (Evaluation.SOURCE_TASK_SUBMISSION.equals(evaluation.sourceType())) {
            inputQuery.findSubmission(evaluation.sourceId()).ifPresent(submission -> {
                config.put("topic", submission.topic());
                config.put("duration_recommended_seconds",
                        submission.durationRecommendedSeconds());
                config.put("duration_max_seconds", submission.durationMaxSeconds());
                putIfPresent(config, "reference_material", submission.referenceMaterial());
                putIfPresent(config, "answer_points", answerPoints(
                        submission.questionConfig()));
                putIfPresent(config, "question_config", submission.questionConfig());
                putIfPresent(config, "config_snapshot", submission.configSnapshot());
            });
        } else {
            inputQuery.findTrainingRecord(evaluation.sourceId()).ifPresent(training -> {
                Map<String, Object> snapshot = training.configSnapshot();
                if (snapshot != null) {
                    snapshot.forEach((key, value) -> {
                        if (!"weights".equals(key)) {
                            config.put(key, value);
                        }
                    });
                }
            });
        }
        return config;
    }

    private List<EvaluationInputManifestView.RecordingView> recordings(
            Evaluation evaluation, InputSnapshot snapshot) {
        List<UUID> allowed = snapshot.recordingIds();
        List<EvaluationInputManifestView.RecordingView> views = new ArrayList<>();
        for (EvaluationInputQuery.RecordingStreamRow stream
                : inputQuery.recordingStreams(evaluation.sessionId())) {
            // The snapshot is authoritative: a stream uploaded after evaluation creation must
            // not become evidence for an already-started job.
            if (!allowed.contains(stream.id())) {
                continue;
            }
            if (!"uploaded".equals(stream.status())
                    || stream.objectKey() == null
                    || stream.objectKey().isBlank()) {
                continue;
            }
            views.add(new EvaluationInputManifestView.RecordingView(
                    stream.id(),
                    stream.streamType(),
                    objectStorage.presignGet(stream.objectKey(), PRESIGN_TTL),
                    stream.durationMs(),
                    stream.offsetMs(),
                    stream.mime()));
        }
        return views;
    }

    private Evaluation requireEvaluation(UUID evaluationId) {
        return evaluationRepository.findById(evaluationId)
                .orElseThrow(() -> notFound("评测不存在"));
    }

    private InputSnapshot requireSnapshot(UUID evaluationId) {
        return snapshotRepository.findByEvaluationId(evaluationId)
                .orElseThrow(() -> notFound("输入快照不存在"));
    }

    private static void putIfPresent(
            Map<String, Object> target, String key, Object value) {
        if (value != null && !(value instanceof Map<?, ?> map && map.isEmpty())) {
            target.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object answerPoints(Map<String, Object> questionConfig) {
        if (questionConfig == null) {
            return null;
        }
        if (questionConfig.containsKey("answer_points")) {
            return questionConfig.get("answer_points");
        }
        Object questions = questionConfig.get("questions");
        if (questions instanceof List<?> list) {
            List<Object> points = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    points.add(map.get("answer_points"));
                }
            }
            return points;
        }
        return null;
    }

    private static int clamp(int limit, int max) {
        if (limit <= 0) {
            return Math.min(50, max);
        }
        return Math.min(limit, max);
    }

    private static EvaluationException notFound(String message) {
        return new EvaluationException(EvaluationErrorCode.EVALUATION_NOT_FOUND, message);
    }

    static Instant snapshotInstant(InputSnapshot snapshot) {
        return snapshot.createdAt();
    }
}
