package com.shijiangtai.m3classroom.application.view;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One replay event. The payload is passed through as stored (JSON-native values only); callers
 * must not assume the producer's original Java types.
 */
public record SessionEventView(
        UUID eventId,
        long seq,
        String type,
        long atMs,
        Map<String, Object> payload) {

    public SessionEventView {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
