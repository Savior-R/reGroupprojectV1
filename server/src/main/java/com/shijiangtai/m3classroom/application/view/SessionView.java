package com.shijiangtai.m3classroom.application.view;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Student-visible session summary (02 §4.3). Contains no privileged task configuration:
 * no reference material, answer points, rubric weights or virtual-student parameters.
 *
 * <p>Serialized snake_case by the global Jackson naming strategy. Timestamps are ISO-8601
 * UTC strings, {@code stream_offsets} is keyed by {@code camera}/{@code screen}/{@code audio},
 * and {@code lastSeq} is the highest persisted event sequence.
 */
public record SessionView(
        UUID sessionId,
        UUID taskId,
        UUID courseId,
        String sessionType,
        String mode,
        String status,
        int attemptNo,
        String startedAt,
        String endedAt,
        Long durationMs,
        Map<String, Long> streamOffsets,
        long lastSeq) {

    public SessionView {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sessionType, "sessionType");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        streamOffsets = streamOffsets == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(streamOffsets));
    }
}
