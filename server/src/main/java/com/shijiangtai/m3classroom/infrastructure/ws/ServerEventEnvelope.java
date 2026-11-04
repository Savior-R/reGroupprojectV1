package com.shijiangtai.m3classroom.infrastructure.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server event envelope from 02 §6.2:
 * {@code {v, type, event_id, seq, at_ms, session_id, payload}}.
 *
 * <p>Persisted events use the actor's {@code event_id}/{@code seq}/{@code at_ms}. Transport-level
 * messages such as {@code session.snapshot}, {@code server.ack}, {@code error} and {@code ping}
 * allocate a fresh {@code event_id}; their {@code seq} is the last event sequence known to the
 * connection so clients never observe a rewinding envelope sequence.
 */
public record ServerEventEnvelope(
        @JsonProperty("v") int v,
        @JsonProperty("type") String type,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("seq") long seq,
        @JsonProperty("at_ms") long atMs,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("payload") Map<String, Object> payload) {

    public static final int VERSION = 1;

    public static final String SESSION_SNAPSHOT = "session.snapshot";
    public static final String SERVER_ACK = "server.ack";
    public static final String ERROR = "error";
    public static final String PING = "ping";

    public ServerEventEnvelope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sessionId, "sessionId");
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** Wraps one actor event; {@code event_id}, {@code seq} and {@code at_ms} come from the event. */
    public static ServerEventEnvelope of(SessionEvent event) {
        Objects.requireNonNull(event, "event");
        return new ServerEventEnvelope(
                VERSION,
                event.type(),
                event.eventId().toString(),
                event.seq(),
                event.atMs(),
                event.sessionId().value().toString(),
                event.payload());
    }

    public static ServerEventEnvelope snapshot(
            SessionId sessionId, long seq, long atMs, Map<String, Object> payload) {
        return transport(SESSION_SNAPSHOT, sessionId, seq, atMs, payload);
    }

    public static ServerEventEnvelope ack(
            SessionId sessionId, long seq, long atMs, String commandId, boolean ok) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("command_id", commandId);
        payload.put("ok", ok);
        return transport(SERVER_ACK, sessionId, seq, atMs, payload);
    }

    public static ServerEventEnvelope error(
            SessionId sessionId, long seq, long atMs, String commandId,
            ErrorCode errorCode, String message) {
        Objects.requireNonNull(errorCode, "errorCode");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", errorCode.name());
        payload.put("message", message);
        if (commandId != null) {
            payload.put("command_id", commandId);
        }
        return transport(ERROR, sessionId, seq, atMs, payload);
    }

    public static ServerEventEnvelope ping(
            SessionId sessionId, long seq, long atMs, long serverTimeMs) {
        return transport(PING, sessionId, seq, atMs, Map.of("server_time_ms", serverTimeMs));
    }

    private static ServerEventEnvelope transport(
            String type, SessionId sessionId, long seq, long atMs,
            Map<String, Object> payload) {
        Objects.requireNonNull(sessionId, "sessionId");
        return new ServerEventEnvelope(
                VERSION, type, UUID.randomUUID().toString(), seq, atMs,
                sessionId.value().toString(), payload);
    }
}
