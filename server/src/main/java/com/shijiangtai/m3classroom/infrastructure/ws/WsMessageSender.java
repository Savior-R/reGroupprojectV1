package com.shijiangtai.m3classroom.infrastructure.ws;

import java.io.IOException;
import java.util.Objects;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes and sends server envelopes on one WebSocket session.
 *
 * <p>Sends are synchronized so the actor event sink, command acks and heartbeat pings cannot
 * interleave partial frames. The caller owns the connection lifecycle; this class only maps a
 * closed or failing session to an {@link IOException}.
 */
public final class WsMessageSender {

    private final WebSocketSession session;
    private final ObjectMapper objectMapper;

    public WsMessageSender(WebSocketSession session, ObjectMapper objectMapper) {
        this.session = Objects.requireNonNull(session, "session");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public synchronized void send(ServerEventEnvelope envelope) throws IOException {
        Objects.requireNonNull(envelope, "envelope");
        if (!session.isOpen()) {
            throw new IOException("WebSocket session is closed");
        }
        session.sendMessage(new TextMessage(
                objectMapper.writeValueAsString(envelope)));
    }
}
