package com.shijiangtai.m4eval.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to M2 / M3 / M6 data needed to assemble an evaluation input (06 §4.3).
 *
 * <p>M4 never writes these tables. The adapter is owned by M4 and queries the published schemas
 * directly; it does not depend on M2/M3/M6 application ports.
 */
public interface EvaluationInputQuery {

    Optional<SubmissionContext> findSubmission(UUID submissionId);

    Optional<TrainingContext> findTrainingRecord(UUID trainingRecordId);

    Optional<SessionInfo> findSession(UUID sessionId);

    List<TextRow> texts(UUID evaluationId, UUID sessionId, UUID afterId, int limit);

    List<FrameRow> frames(
            UUID evaluationId, UUID sessionId, Long afterTimestampMs, UUID afterId, int limit);

    List<EventRow> events(UUID evaluationId, UUID sessionId, Long afterSeq, int limit);

    List<RecordingStreamRow> recordingStreams(UUID sessionId);

    List<UUID> textIds(UUID sessionId);

    List<UUID> frameIds(UUID sessionId);

    List<UUID> eventIds(UUID sessionId);

    List<UUID> recordingStreamIds(UUID sessionId);

    long countTexts(UUID sessionId);

    long countFrames(UUID sessionId);

    long countEvents(UUID sessionId);

    boolean isTeacherInGroup(UUID courseId, UUID teacherId);

    Optional<UUID> findCourseSchoolId(UUID courseId);

    record SubmissionContext(
            UUID submissionId,
            UUID taskId,
            UUID sessionId,
            UUID studentAccountId,
            UUID courseId,
            String mode,
            Map<String, Integer> weights,
            int taskConfigVersion,
            Map<String, Object> configSnapshot,
            Map<String, Object> referenceMaterial,
            Map<String, Object> questionConfig,
            String topic,
            int durationRecommendedSeconds,
            int durationMaxSeconds) {
    }

    record TrainingContext(
            UUID trainingRecordId,
            UUID sessionId,
            UUID studentAccountId,
            UUID courseId,
            UUID templateId,
            String mode,
            Map<String, Integer> weights,
            int trainingConfigVersion,
            Map<String, Object> configSnapshot,
            String status,
            Instant expiresAt) {
    }

    record SessionInfo(
            UUID sessionId,
            String sessionType,
            UUID taskId,
            UUID courseId,
            UUID studentAccountId,
            String mode,
            String status,
            UUID recordingId,
            Long durationMs,
            Map<String, Object> streamOffsets,
            Instant startedAt,
            Instant endedAt) {
    }

    record TextRow(
            UUID id,
            String speakerType,
            UUID speakerRef,
            String source,
            String kind,
            String text,
            Long startMs,
            Long endMs,
            long atMs,
            Double confidence,
            String modelVersion,
            String promptVersion) {
    }

    record FrameRow(
            UUID id,
            long timestampMs,
            String text,
            Double confidence,
            String frameType,
            String objectKey,
            String contentHash,
            String status) {
    }

    record EventRow(
            UUID id,
            long seq,
            String type,
            long timestampMs,
            String actor,
            String target,
            Map<String, Object> payload) {
    }

    record RecordingStreamRow(
            UUID id,
            String streamType,
            String objectKey,
            Long durationMs,
            long offsetMs,
            String mime,
            String status) {
    }
}
