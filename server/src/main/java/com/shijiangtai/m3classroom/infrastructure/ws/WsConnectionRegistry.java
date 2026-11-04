package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.shared.domain.SessionId;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Thread-safe map of the single active control-WebSocket connection per session (05A §8).
 *
 * <p>Kept free of Spring and Jakarta WebSocket types so the application layer can attach a
 * {@link SessionEventSinkAdapter} without a framework dependency. A session may be disconnected
 * during the 30 s grace window; {@link #send} then drops the event because the actor still keeps
 * it in its context and {@code session.resume} replays it. Registering a second connection
 * replaces the first; the returned previous sink lets the handler close the stale socket without
 * triggering a grace failure.
 */
public final class WsConnectionRegistry {

    private final Map<SessionId, Consumer<SessionEvent>> connections =
            new ConcurrentHashMap<>();

    /**
     * Registers (or replaces) the active connection for a session.
     *
     * @return the sink it replaced, or {@code null} when this was the first connection
     */
    public Consumer<SessionEvent> register(
            SessionId sessionId, Consumer<SessionEvent> sink) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(sink, "sink");
        return connections.put(sessionId, sink);
    }

    /** Pushes an event to the current connection; a missing connection is a no-op. */
    public void send(SessionId sessionId, SessionEvent event) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(event, "event");
        Consumer<SessionEvent> sink = connections.get(sessionId);
        if (sink != null) {
            sink.accept(event);
        }
    }

    /**
     * Removes the connection only when it is still the registered one, so a late close of the
     * old socket cannot unregister its replacement.
     *
     * @return {@code true} when this call removed the connection
     */
    public boolean remove(SessionId sessionId, Consumer<SessionEvent> sink) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(sink, "sink");
        return connections.remove(sessionId, sink);
    }

    public boolean isConnected(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return connections.containsKey(sessionId);
    }
}
