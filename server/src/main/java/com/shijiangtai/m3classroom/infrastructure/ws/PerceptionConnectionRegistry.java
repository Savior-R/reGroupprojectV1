package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.shared.domain.SessionId;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tracks the single active perception WebSocket per session (05A §6.2).
 *
 * <p>A second connection replaces the first, which is closed normally (1000). Every registration
 * subscribes to {@link SessionActor#addStatusListener}; when the actor reaches a terminal status
 * the current socket is closed 4002 and the listener is removed. {@link #unregister} removes the
 * mapping only when it still points at the given socket, so a late close of a replaced socket
 * cannot unregister its replacement.
 */
@Component
public final class PerceptionConnectionRegistry {

    static final int CLOSE_REPLACED = CloseStatus.NORMAL.getCode();
    static final int CLOSE_TERMINAL = 4002;

    private static final Logger LOG =
            LoggerFactory.getLogger(PerceptionConnectionRegistry.class);

    private final Map<SessionId, Connection> connections = new ConcurrentHashMap<>();

    public void register(SessionId sessionId, WebSocketSession socket, SessionActor actor) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(actor, "actor");

        Connection connection = new Connection(sessionId, socket);
        Connection previous = connections.put(sessionId, connection);
        connection.listenerRemover = actor.addStatusListener(
                status -> onStatusChanged(sessionId, connection, status));
        if (previous != null) {
            previous.removeListener();
            previous.close(CLOSE_REPLACED, "被新连接替换");
        }
    }

    /**
     * Removes the session mapping only when it still points at {@code socket}, so the close of a
     * replaced socket cannot evict the newer connection.
     */
    public void unregister(SessionId sessionId, WebSocketSession socket) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(socket, "socket");
        Connection current = connections.get(sessionId);
        if (current == null || current.socket != socket) {
            return;
        }
        if (connections.remove(sessionId, current)) {
            current.removeListener();
        }
    }

    private void onStatusChanged(
            SessionId sessionId, Connection connection, SessionStatus status) {
        if (!isTerminal(status)) {
            return;
        }
        if (connections.remove(sessionId, connection)) {
            connection.removeListener();
        }
        connection.close(CLOSE_TERMINAL, "会话已结束");
    }

    private static boolean isTerminal(SessionStatus status) {
        return status == SessionStatus.ENDED
                || status == SessionStatus.REVIEWING
                || status == SessionStatus.SUBMITTED
                || status == SessionStatus.CANCELLED
                || status == SessionStatus.FAILED;
    }

    /** Per-session registration; closing is idempotent. */
    private static final class Connection {

        private final SessionId sessionId;
        private final WebSocketSession socket;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Runnable listenerRemover;

        private Connection(SessionId sessionId, WebSocketSession socket) {
            this.sessionId = sessionId;
            this.socket = socket;
        }

        private void removeListener() {
            Runnable remover = listenerRemover;
            listenerRemover = null;
            if (remover != null) {
                try {
                    remover.run();
                } catch (RuntimeException exception) {
                    LOG.debug("Failed to remove perception status listener: {}",
                            exception.toString());
                }
            }
        }

        private void close(int code, String reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                synchronized (socket) {
                    if (socket.isOpen()) {
                        socket.close(new CloseStatus(code, reason));
                    }
                }
            } catch (IOException exception) {
                // The container still invokes afterConnectionClosed for cleanup.
                LOG.debug("Failed to close perception socket for session {}: {}",
                        sessionId.value(), exception.toString());
            }
        }
    }
}
