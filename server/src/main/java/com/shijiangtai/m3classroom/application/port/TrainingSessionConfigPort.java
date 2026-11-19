package com.shijiangtai.m3classroom.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * M6 → M3 training configuration seam.
 *
 * <p>Training sessions are created by M6 as {@code m3_classroom.sessions} rows with
 * {@code session_type = 'training'} and no task. The M3 classroom/recording runtime still needs
 * a published-config-shaped snapshot (topic, duration, virtual students), so this port exposes
 * M6's effective config to M3 without making M3 depend on M6 classes.
 */
public interface TrainingSessionConfigPort {

    Optional<TrainingSessionConfig> find(UUID sessionId);

    /** No-op implementation for unit tests that only exercise task sessions. */
    static TrainingSessionConfigPort unsupported() {
        return sessionId -> Optional.empty();
    }

    record TrainingSessionConfig(
            UUID courseId,
            UUID schoolId,
            UUID studentAccountId,
            String topic,
            int recommendedSeconds,
            int maxSeconds,
            Map<String, Integer> weights,
            List<VirtualStudent> virtualStudents,
            Instant createdAt) {
    }

    record VirtualStudent(
            UUID id,
            String name,
            String avatar,
            String personalityText,
            BigDecimal activity,
            BigDecimal questionRate,
            BigDecimal ability,
            BigDecimal attentionBaseline,
            BigDecimal disciplineProbability,
            int seatNo,
            int sortOrder) {
    }
}
