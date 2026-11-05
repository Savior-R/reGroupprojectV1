package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.shared.domain.SessionId;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tracks the single active perception WebSocket per session (05A §6.2).
 *
 * <p>A second connection replaces the first: the previous handler is frozen synchronously through
 * the {@code onFrozen} callback <em>before</em> its socket is closed normally (1000). Freezing stops
 * the stale handler from accepting audio/finals, persisting text or reaching the actor; relying on
 * the container's later {@code afterConnectionClosed} would leave a window in which the replaced
 * connection could still mutate the session.
 *
 * <p>Every registration subscribes to {@link SessionActor#addStatusListener}. When the actor
 * reaches a terminal status the current socket is closed 4002 on a dedicated daemon executor, so a
 * stalled client can never block {@code session.end}/{@code fail}/{@code cancel} while the actor
 * monitor is held. The close task freezes the connection first. {@link #unregister} removes the
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
    private final ExecutorService closeExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "perception-terminal-close");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Registers {@code socket} as the current perception connection for the session. A previous
     * connection is frozen (through its {@code onFrozen} callback) synchronously and only then
     * closed with 1000.
     */
    public void register(SessionId sessionId, WebSocketSession socket, SessionActor actor,
            Runnable onFrozen) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(onFrozen, "onFrozen");

        Connection connection = new Connection(sessionId, socket, onFrozen);
        Connection previous = connections.put(sessionId, connection);
        connection.listenerRemover = actor.addStatusListener(
                status -> onStatusChanged(sessionId, connection, status));
        if (previous != null) {
            previous.removeListener();
            previous.replace();
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

    /**
     * Runs on the actor thread while the actor monitor is held. It only removes the mapping and
     * hands the blocking close to {@link #closeExecutor}; the socket is frozen inside that task.
     */
    private void onStatusChanged(
            SessionId sessionId, Connection connection, SessionStatus status) {
        if (!isTerminal(status)) {
            return;
        }
        if (connections.remove(sessionId, connection)) {
            connection.removeListener();
        }
        connection.closeOnExecutor(CLOSE_TERMINAL, "会话已结束");
    }

    private static boolean isTerminal(SessionStatus status) {
        return status == SessionStatus.ENDED
                || status == SessionStatus.REVIEWING
                || status == SessionStatus.SUBMITTED
                || status == SessionStatus.CANCELLED
                || status == SessionStatus.FAILED;
    }

    /** Stops and releases the terminal-close executor when the application context shuts down. */
    @PreDestroy
    void shutdown() {
        closeExecutor.shutdownNow();
    }

    /** Per-session registration; freezing and closing are idempotent. */
    private final class Connection {

        private final SessionId sessionId;
        private final WebSocketSession socket;
        private final Runnable onFrozen;
        private final AtomicBoolean frozen = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Runnable listenerRemover;

        private Connection(SessionId sessionId, WebSocketSession socket, Runnable onFrozen) {
            this.sessionId = sessionId;
            this.socket = socket;
            this.onFrozen = onFrozen;
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

        /** Replaced: freeze the stale handler synchronously, then close the old socket 1000. */
        private void replace() {
            freeze();
            close(CLOSE_REPLACED, "被新连接替换");
        }

        private void closeOnExecutor(int code, String reason) {
            try {
                closeExecutor.execute(() -> {
                    freeze();
                    close(code, reason);
                });
            } catch (RejectedExecutionException exception) {
                // Context shutdown: there is no actor monitor to protect any more.
                LOG.debug("Perception close executor rejected close for session {}: {}",
                        sessionId.value(), exception.toString());
                freeze();
                close(code, reason);
            }
        }

        private void freeze() {
            if (!frozen.compareAndSet(false, true)) {
                return;
            }
            try {
                onFrozen.run();
            } catch (RuntimeException exception) {
                LOG.warn("Failed to freeze perception connection for session {}: {}",
                        sessionId.value(), exception.toString());
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
