package com.shijiangtai.m3classroom.domain.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Student-facing session snapshot (05A §8, 02 §6.4).
 *
 * <p>Contains only public configuration and runtime state. Rubric weights, reference material,
 * answer points, virtual-student parameters and personality text are never included.
 */
public record SessionSnapshot(
        SessionStatus status,
        String mode,
        Map<String, Object> publicConfig,
        List<Map<String, Object>> virtualStudents,
        Map<String, Object> interview,
        Map<String, Object> recording,
        long lastSeq,
        long elapsedMs) {

    public SessionSnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(mode, "mode");
        publicConfig = copyMap(publicConfig);
        virtualStudents = virtualStudents == null ? List.of() : List.copyOf(virtualStudents);
        interview = copyMap(interview);
        recording = copyMap(recording);
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
