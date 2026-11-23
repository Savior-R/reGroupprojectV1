package com.shijiangtai.m4eval.application;

import com.shijiangtai.m4eval.application.port.AfterCommitPort;
import com.shijiangtai.m4eval.application.port.DimensionScoreRepository;
import com.shijiangtai.m4eval.application.port.EvaluationInputQuery;
import com.shijiangtai.m4eval.application.port.EvaluationInputSnapshotRepository;
import com.shijiangtai.m4eval.application.port.EvaluationIssueRepository;
import com.shijiangtai.m4eval.application.port.EvaluationJobRepository;
import com.shijiangtai.m4eval.application.port.EvaluationQueue;
import com.shijiangtai.m4eval.application.port.EvaluationRepository;
import com.shijiangtai.m4eval.domain.DimensionCatalog;
import com.shijiangtai.m4eval.domain.DimensionScore;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationIssue;
import com.shijiangtai.m4eval.domain.EvaluationJob;
import com.shijiangtai.m4eval.domain.EvaluationJobStatus;
import com.shijiangtai.m4eval.domain.EvaluationMode;
import com.shijiangtai.m4eval.domain.EvaluationStatus;
import com.shijiangtai.m4eval.domain.InputSnapshot;
import com.shijiangtai.m4eval.domain.WeightDefaults;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.EventPublisher;
import com.shijiangtai.shared.application.IdGenerator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hand-written ports for M4 application tests. The module has no in-memory adapter in
 * production, so the tests exercise the orchestration with deterministic doubles instead of a
 * database.
 */
final class M4TestDoubles {

    static final Instant NOW = Instant.parse("2026-11-23T08:00:00Z");
    static final UUID SESSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID COURSE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID STUDENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID SCHOOL_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    static final UUID OTHER_SCHOOL_ID =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

    private M4TestDoubles() {
    }

    static final class MutableClock implements ClockProvider {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }
    }

    static final class SequentialIds implements IdGenerator {
        private long next;

        SequentialIds(long start) {
            this.next = start;
        }

        @Override
        public UUID nextId() {
            return new UUID(0, next++);
        }
    }

    static final class ImmediateAfterCommit implements AfterCommitPort {
        private final List<Runnable> actions = new ArrayList<>();

        @Override
        public void afterCommit(Runnable action) {
            action.run();
            actions.add(action);
        }

        @Override
        public <T> T afterCommit(java.util.function.Supplier<T> action) {
            return action.get();
        }

        int count() {
            return actions.size();
        }
    }

    static final class RecordingQueue implements EvaluationQueue {
        final List<EvaluationDispatchMessage> published = new ArrayList<>();
        final List<EvaluationDispatchMessage> deadLettered = new ArrayList<>();
        String deadLetterReason;
        boolean failPublish;

        @Override
        public void publish(EvaluationDispatchMessage message) {
            if (failPublish) {
                throw new IllegalStateException("redis unavailable");
            }
            published.add(message);
        }

        @Override
        public void deadLetter(EvaluationDispatchMessage message, String reason) {
            deadLettered.add(message);
            deadLetterReason = reason;
        }
    }

    static final class RecordingEvents implements EventPublisher {
        final List<Object> events = new ArrayList<>();

        @Override
        public void publish(Object event) {
            events.add(event);
        }

        List<com.shijiangtai.m4eval.domain.EvaluationTerminalEvent> terminals() {
            return events.stream()
                    .filter(com.shijiangtai.m4eval.domain.EvaluationTerminalEvent.class::
                            isInstance)
                    .map(com.shijiangtai.m4eval.domain.EvaluationTerminalEvent.class::cast)
                    .toList();
        }
    }

    static final class InMemoryEvaluations implements EvaluationRepository {
        final Map<UUID, Evaluation> byId = new LinkedHashMap<>();
        final List<UUID> submissionsWithoutEvaluation = new ArrayList<>();
        final List<UUID> trainingRecordsWithoutEvaluation = new ArrayList<>();

        @Override
        public Evaluation save(Evaluation evaluation) {
            byId.put(evaluation.id(), evaluation);
            return evaluation;
        }

        @Override
        public Optional<Evaluation> findById(UUID evaluationId) {
            return Optional.ofNullable(byId.get(evaluationId));
        }

        @Override
        public Optional<Evaluation> findBySource(String sourceType, UUID sourceId) {
            return byId.values().stream()
                    .filter(evaluation -> evaluation.sourceType().equals(sourceType)
                            && evaluation.sourceId().equals(sourceId))
                    .findFirst();
        }

        @Override
        public List<UUID> findSubmissionIdsWithoutEvaluation(int limit) {
            return submissionsWithoutEvaluation.stream().limit(limit).toList();
        }

        @Override
        public List<UUID> findTrainingRecordIdsWithoutEvaluation(int limit) {
            return trainingRecordsWithoutEvaluation.stream().limit(limit).toList();
        }
    }

    static final class InMemoryJobs implements EvaluationJobRepository {
        final Map<UUID, EvaluationJob> byId = new LinkedHashMap<>();

        @Override
        public EvaluationJob save(EvaluationJob job) {
            byId.put(job.id(), job);
            return job;
        }

        @Override
        public Optional<EvaluationJob> findById(UUID jobId) {
            return Optional.ofNullable(byId.get(jobId));
        }

        @Override
        public Optional<EvaluationJob> findByEvaluationId(UUID evaluationId) {
            return byId.values().stream()
                    .filter(job -> job.evaluationId().equals(evaluationId))
                    .findFirst();
        }

        @Override
        public List<EvaluationJob> findDispatchable(Instant now, int limit) {
            return byId.values().stream()
                    .filter(job -> job.status() == EvaluationJobStatus.QUEUED)
                    .filter(job -> job.nextRetryAt() != null
                            && !job.nextRetryAt().isAfter(now))
                    .sorted(Comparator.comparing(EvaluationJob::createdAt))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<EvaluationJob> findStaleRunning(Instant staleBefore, int limit) {
            return byId.values().stream()
                    .filter(job -> job.status() == EvaluationJobStatus.RUNNING)
                    .filter(job -> job.updatedAt().isBefore(staleBefore))
                    .limit(limit)
                    .toList();
        }

        EvaluationJob only() {
            return byId.values().stream().findFirst().orElseThrow();
        }
    }

    static final class InMemoryDimensions implements DimensionScoreRepository {
        final Map<UUID, List<DimensionScore>> byEvaluation = new LinkedHashMap<>();

        @Override
        public void replaceAll(UUID evaluationId, List<DimensionScore> scores) {
            byEvaluation.put(evaluationId, List.copyOf(scores));
        }

        @Override
        public List<DimensionScore> findByEvaluationId(UUID evaluationId) {
            return byEvaluation.getOrDefault(evaluationId, List.of());
        }
    }

    static final class InMemoryIssues implements EvaluationIssueRepository {
        final Map<UUID, List<EvaluationIssue>> byEvaluation = new LinkedHashMap<>();

        @Override
        public void replaceAll(UUID evaluationId, List<EvaluationIssue> issues) {
            byEvaluation.put(evaluationId, List.copyOf(issues));
        }

        @Override
        public List<EvaluationIssue> findByEvaluationId(UUID evaluationId) {
            return byEvaluation.getOrDefault(evaluationId, List.of());
        }
    }

    static final class InMemorySnapshots implements EvaluationInputSnapshotRepository {
        final Map<UUID, InputSnapshot> byEvaluation = new LinkedHashMap<>();

        @Override
        public InputSnapshot save(InputSnapshot snapshot) {
            byEvaluation.put(snapshot.evaluationId(), snapshot);
            return snapshot;
        }

        @Override
        public Optional<InputSnapshot> findByEvaluationId(UUID evaluationId) {
            return Optional.ofNullable(byEvaluation.get(evaluationId));
        }
    }

    static final class FakeObjectStorage implements RecordingObjectStorage {
        final List<String> presigned = new ArrayList<>();
        final List<Duration> presignedTtls = new ArrayList<>();

        @Override
        public RecordingObjectStorage.MultipartUpload createMultipart(String objectKey, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String presignPart(
                String objectKey, String uploadId, int partNumber, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void completeMultipart(
                String objectKey, String uploadId, List<RecordingObjectStorage.PartEtag> parts) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abortMultipart(String objectKey, String uploadId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String presignGet(String objectKey, Duration ttl) {
            presigned.add(objectKey);
            presignedTtls.add(ttl);
            return "https://minio.test/" + objectKey + "?sig=test";
        }

        @Override
        public RecordingObjectStorage.ObjectStat stat(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            throw new UnsupportedOperationException();
        }
    }

    /** Stub of outside-module reads; only the fields a test sets are meaningful. */
    static final class StubInputQuery implements EvaluationInputQuery {
        EvaluationInputQuery.SubmissionContext submission;
        EvaluationInputQuery.TrainingContext training;
        EvaluationInputQuery.SessionInfo session;
        long textCount;
        long frameCount;
        long eventCount;
        List<UUID> recordingIds = List.of();
        List<UUID> textIds = List.of();
        List<UUID> frameIds = List.of();
        List<UUID> eventIds = List.of();
        List<EvaluationInputQuery.RecordingStreamRow> recordings = List.of();
        final Set<String> teacherGroups = new LinkedHashSet<>();
        final Map<UUID, UUID> courseSchools = new LinkedHashMap<>();
        List<EvaluationInputQuery.TextRow> textRows = List.of();
        List<EvaluationInputQuery.FrameRow> frameRows = List.of();
        List<EvaluationInputQuery.EventRow> eventRows = List.of();
        boolean sessionLookupFails;

        @Override
        public Optional<EvaluationInputQuery.SubmissionContext> findSubmission(UUID submissionId) {
            if (submission == null || !submission.submissionId().equals(submissionId)) {
                return Optional.empty();
            }
            return Optional.of(submission);
        }

        @Override
        public Optional<EvaluationInputQuery.TrainingContext> findTrainingRecord(UUID trainingRecordId) {
            if (training == null || !training.trainingRecordId().equals(trainingRecordId)) {
                return Optional.empty();
            }
            return Optional.of(training);
        }

        @Override
        public Optional<EvaluationInputQuery.SessionInfo> findSession(UUID sessionId) {
            if (sessionLookupFails || session == null || !session.sessionId().equals(sessionId)) {
                return Optional.empty();
            }
            return Optional.of(session);
        }

        @Override
        public List<EvaluationInputQuery.TextRow> texts(
                UUID evaluationId, UUID sessionId, UUID afterId, int limit) {
            return textRows.stream().limit(limit).toList();
        }

        @Override
        public List<EvaluationInputQuery.FrameRow> frames(
                UUID evaluationId, UUID sessionId, Long afterTimestampMs, UUID afterId,
                int limit) {
            return frameRows.stream().limit(limit).toList();
        }

        @Override
        public List<EvaluationInputQuery.EventRow> events(UUID evaluationId, UUID sessionId, Long afterSeq, int limit) {
            return eventRows.stream().limit(limit).toList();
        }

        @Override
        public List<EvaluationInputQuery.RecordingStreamRow> recordingStreams(UUID sessionId) {
            return recordings;
        }

        @Override
        public List<UUID> textIds(UUID sessionId) {
            return textIds;
        }

        @Override
        public List<UUID> frameIds(UUID sessionId) {
            return frameIds;
        }

        @Override
        public List<UUID> eventIds(UUID sessionId) {
            return eventIds;
        }

        @Override
        public List<UUID> recordingStreamIds(UUID sessionId) {
            return recordingIds;
        }

        @Override
        public long countTexts(UUID sessionId) {
            return textCount;
        }

        @Override
        public long countFrames(UUID sessionId) {
            return frameCount;
        }

        @Override
        public long countEvents(UUID sessionId) {
            return eventCount;
        }

        @Override
        public boolean isTeacherInGroup(UUID courseId, UUID teacherId) {
            return teacherGroups.contains(courseId + ":" + teacherId);
        }

        @Override
        public Optional<UUID> findCourseSchoolId(UUID courseId) {
            return Optional.ofNullable(courseSchools.get(courseId));
        }

        void teacherInGroup(UUID courseId, UUID teacherId) {
            teacherGroups.add(courseId + ":" + teacherId);
        }
    }

    static EvaluationInputQuery.SubmissionContext submission(UUID submissionId) {
        return new EvaluationInputQuery.SubmissionContext(
                submissionId,
                UUID.fromString("77777777-7777-7777-7777-777777777777"),
                SESSION_ID,
                STUDENT_ID,
                COURSE_ID,
                "free",
                Map.of("content", 30, "expression", 20, "interaction", 20, "board", 15,
                        "time", 15),
                4,
                Map.of("weights", Map.of("content", 30)),
                Map.of("text", "参考材料"),
                Map.of("questions", List.of(Map.of("answer_points", List.of("要点一")))),
                "分数的初步认识",
                900,
                1200);
    }

    static EvaluationInputQuery.SessionInfo session() {
        return new EvaluationInputQuery.SessionInfo(
                SESSION_ID, "task", null, COURSE_ID, STUDENT_ID, "free", "ended", null,
                900_000L, Map.of("audio", 120L), NOW.minusSeconds(1200),
                NOW.minusSeconds(300));
    }

    static EvaluationInputQuery.TrainingContext training(UUID recordId, String status, Instant expiresAt) {
        return new EvaluationInputQuery.TrainingContext(
                recordId,
                SESSION_ID,
                STUDENT_ID,
                COURSE_ID,
                UUID.fromString("88888888-8888-8888-8888-888888888888"),
                "free",
                WeightDefaults.forMode(EvaluationMode.FREE),
                2,
                Map.of("reference_material", "训练参考材料"),
                status,
                expiresAt);
    }

    static Evaluation pendingSubmission(UUID evaluationId, UUID submissionId) {
        return Evaluation.rehydrate(
                evaluationId,
                Evaluation.SOURCE_TASK_SUBMISSION,
                submissionId,
                submissionId,
                null,
                SESSION_ID,
                null,
                COURSE_ID,
                STUDENT_ID,
                EvaluationMode.FREE,
                EvaluationStatus.PENDING,
                null,
                WeightDefaults.forMode(EvaluationMode.FREE),
                4,
                null,
                null,
                null,
                DimensionCatalog.RUBRIC_VERSION,
                0,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                NOW,
                null,
                NOW);
    }

    static Evaluation pendingTraining(UUID evaluationId, UUID recordId) {
        return Evaluation.rehydrate(
                evaluationId,
                Evaluation.SOURCE_TRAINING_RECORD,
                recordId,
                null,
                recordId,
                SESSION_ID,
                null,
                COURSE_ID,
                STUDENT_ID,
                EvaluationMode.FREE,
                EvaluationStatus.PENDING,
                null,
                WeightDefaults.forMode(EvaluationMode.FREE),
                null,
                2,
                null,
                null,
                DimensionCatalog.RUBRIC_VERSION,
                0,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                NOW,
                null,
                NOW);
    }

    static Evaluation pendingTrainingWithoutCourse(UUID evaluationId, UUID recordId) {
        return Evaluation.rehydrate(
                evaluationId,
                Evaluation.SOURCE_TRAINING_RECORD,
                recordId,
                null,
                recordId,
                SESSION_ID,
                null,
                null,
                STUDENT_ID,
                EvaluationMode.FREE,
                EvaluationStatus.PENDING,
                null,
                WeightDefaults.forMode(EvaluationMode.FREE),
                null,
                2,
                null,
                null,
                DimensionCatalog.RUBRIC_VERSION,
                0,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                NOW,
                null,
                NOW);
    }

    static EvaluationJob queuedJob(UUID jobId, UUID evaluationId) {
        return EvaluationJob.rehydrate(
                jobId, evaluationId, EvaluationJobStatus.QUEUED, 1, null, NOW, NOW, NOW);
    }

    static DimensionScore dimension(UUID evaluationId, String dimension, String score) {
        Map<String, BigDecimal> subs = DimensionCatalog.subItems(dimension).stream()
                .collect(Collectors.toMap(
                        item -> item,
                        item -> new BigDecimal(score),
                        (left, right) -> left,
                        LinkedHashMap::new));
        return new DimensionScore(
                UUID.randomUUID(),
                evaluationId,
                dimension,
                new BigDecimal(score),
                subs,
                Map.of(),
                new BigDecimal("0.90"));
    }

    static DimensionScore dimension(UUID evaluationId, String name) {
        return dimension(evaluationId, name, "80.0");
    }

    static EvaluationIssue suggestion(
            UUID evaluationId, String dimension, int rank, long startMs) {
        return new EvaluationIssue(
                UUID.randomUUID(),
                evaluationId,
                dimension,
                startMs,
                startMs + 1000,
                "medium",
                "证据",
                "建议",
                true,
                rank,
                0);
    }
}
