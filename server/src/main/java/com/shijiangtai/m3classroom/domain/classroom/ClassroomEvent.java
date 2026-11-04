package com.shijiangtai.m3classroom.domain.classroom;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A classroom event emitted by the class-state domain layer.
 *
 * <p>The payload is copied defensively on construction and exposed as an unmodifiable map;
 * {@code null} values are permitted so that optional protocol fields (for example
 * {@code qa_phase} outside a QA state) can still appear in the payload.
 */
public record ClassroomEvent(String type, Map<String, Object> payload) {

    public ClassroomEvent {
        Objects.requireNonNull(type, "type");
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
