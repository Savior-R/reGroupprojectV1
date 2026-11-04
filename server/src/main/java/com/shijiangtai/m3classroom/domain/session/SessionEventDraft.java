package com.shijiangtai.m3classroom.domain.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Event content supplied by the session layer before the context assigns its id/seq/createdAt.
 */
public record SessionEventDraft(String type, Map<String, Object> payload) {

    public SessionEventDraft {
        Objects.requireNonNull(type, "type");
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static SessionEventDraft of(String type, Map<String, Object> payload) {
        return new SessionEventDraft(type, payload);
    }
}
