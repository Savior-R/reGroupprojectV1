package com.shijiangtai.m6training.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * M3 classroom seam for training sessions.
 *
 * <p>M3 currently models task sessions only; M6 writes the training row directly into
 * {@code m3_classroom.sessions} ({@code session_type=training}, {@code task_id=null}).
 * When M3 exposes {@code createTrainingSession}, only this adapter changes.
 */
public interface TrainingClassroomPort {

    void create(UUID sessionId, UUID studentAccountId, UUID courseId, Instant now);

    Optional<ClassroomSession> find(UUID sessionId);

    void updateStatus(UUID sessionId, String status, Instant endedAt, Long durationMs);

    boolean allStreamsUploaded(UUID sessionId);

    List<String> recordingObjectKeys(UUID sessionId);

    /** Hard-deletes the M3 session; recordings/texts/frames/events cascade. */
    void delete(UUID sessionId);

    record ClassroomSession(
            UUID id,
            UUID studentAccountId,
            UUID courseId,
            String status,
            Instant startedAt,
            Instant endedAt,
            Long durationMs,
            Instant createdAt) {
    }
}
