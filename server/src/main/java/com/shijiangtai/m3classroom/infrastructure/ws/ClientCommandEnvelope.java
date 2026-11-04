package com.shijiangtai.m3classroom.infrastructure.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Client command envelope from 02 §6.2: {@code {v, type, command_id, payload}}.
 *
 * <p>The payload is kept as an unmodifiable map so the parser and command mapper cannot mutate a
 * decoded frame; JSON nulls inside the payload are preserved (optional fields such as
 * {@code hand_raise_id} may be absent or null).
 */
public record ClientCommandEnvelope(
        @JsonProperty("v") int v,
        @JsonProperty("type") String type,
        @JsonProperty("command_id") String commandId,
        @JsonProperty("payload") Map<String, Object> payload) {

    public ClientCommandEnvelope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(commandId, "commandId");
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
