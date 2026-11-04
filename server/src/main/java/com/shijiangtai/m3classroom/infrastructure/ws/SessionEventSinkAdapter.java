package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.shared.domain.SessionId;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link Consumer} handed to {@link com.shijiangtai.m3classroom.application.SessionActor} as its
 * event sink; forwards every actor event to the session's current control-WebSocket connection.
 *
 * <p>The sink is framework-light by design: serialization and the server envelope belong to the
 * Task 12 handler, which registers a serializing consumer in {@link WsConnectionRegistry}. Events
 * emitted while no connection exists (grace window) are dropped here and replayed from
 * {@code SessionActor.replayAfter(lastSeq)} on {@code session.resume}.
 */
public final class SessionEventSinkAdapter implements Consumer<SessionEvent> {

    private final WsConnectionRegistry connections;
    private final SessionId sessionId;

    public SessionEventSinkAdapter(WsConnectionRegistry connections, SessionId sessionId) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    @Override
    public void accept(SessionEvent event) {
        connections.send(sessionId, event);
    }
}
