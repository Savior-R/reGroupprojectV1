package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.shared.domain.SessionId;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link Consumer} handed to {@link com.shijiangtai.m3classroom.application.SessionActor} as its
 * event sink; forwards every actor event to the session's current control-WebSocket connection.
 *
 * <p>This is the single live push path: the Task 12 handler sends only {@code server.ack}/
 * {@code error} envelopes itself and must not resend {@code CommandResult.events()}. On
 * {@code session.resume} the actor pushes {@code session.resumed} followed by the replay tail
 * through this sink. The sink is framework-light by design: serialization and the server envelope
 * belong to the Task 12 handler, which registers a serializing consumer in
 * {@link WsConnectionRegistry}. Events emitted while no connection exists (grace window) are
 * dropped here and replayed from {@code SessionActor.replayAfter(lastSeq)} on resume.
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
