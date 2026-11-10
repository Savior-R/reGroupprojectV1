package com.shijiangtai.m4eval.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable input snapshot captured at evaluation creation (06 §4.4). */
public record InputSnapshot(
        UUID evaluationId,
        String sourceType,
        UUID sourceId,
        UUID submissionId,
        UUID trainingRecordId,
        List<UUID> recordingIds,
        List<UUID> textIds,
        List<UUID> frameIds,
        List<UUID> eventIds,
        int configVersion,
        Integer taskConfigVersion,
        Integer trainingConfigVersion,
        Map<String, Object> flags,
        Instant createdAt) {

    public InputSnapshot {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        recordingIds = List.copyOf(recordingIds == null ? List.of() : recordingIds);
        textIds = List.copyOf(textIds == null ? List.of() : textIds);
        frameIds = List.copyOf(frameIds == null ? List.of() : frameIds);
        eventIds = List.copyOf(eventIds == null ? List.of() : eventIds);
        flags = Collections.unmodifiableMap(
                new LinkedHashMap<>(flags == null ? Map.of() : flags));
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
