package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.shared.domain.SessionId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One append-only session event (05A §6.2).
 *
 * <p>{@code seq} is monotonic per session and starts at 1; {@code atMs} is the session-relative
 * millisecond offset from {@code session.started} (t0). The payload is copied defensively and
 * exposed read-only.
 */
public record SessionEvent(
        UUID eventId,
        SessionId sessionId,
        long seq,
        String type,
        long atMs,
        Map<String, Object> payload,
        Instant createdAt) {

    public SessionEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        if (seq <= 0) {
            throw new IllegalArgumentException("seq must be positive");
        }
        if (atMs < 0) {
            throw new IllegalArgumentException("atMs must not be negative");
        }
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
