package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.port.SchedulerPort;
import com.shijiangtai.m3classroom.domain.session.CommandResult;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionSnapshot;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Raw control-WebSocket handler for {@code /ws/v1/sessions/{sessionId}?ticket=...} (02 §6).
 *
 * <p>The actor's event sink is the single live push path: command events and scheduled/tick
 * events reach the client through {@link WsConnectionRegistry} exactly once. This handler sends
 * only {@code session.snapshot}, {@code server.ack}, {@code error} and {@code ping} itself and
 * deliberately never re-sends {@link CommandResult#events()}.
 *
 * <p>A session has at most one active connection. A new connection replaces the old sink and
 * closes the previous socket normally; because the registry no longer contains the old sink, that
 * close does not start the 30 s grace timer. A genuine disconnect unregisters the sink and calls
 * {@link SessionActor#onWsDisconnected(Instant)}.
 */
@Component
public final class ClassroomWebSocketHandler extends TextWebSocketHandler {

    static final int CLOSE_AUTH = 4001;
    static final int CLOSE_FAILED = 4002;
    static final int CLOSE_STATE = 4003;
    static final int CLOSE_HEARTBEAT = 4004;
    static final int CLOSE_TOO_LARGE = 4005;

    private static final Logger LOG =
            LoggerFactory.getLogger(ClassroomWebSocketHandler.class);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(15);
    private static final Duration PONG_TIMEOUT = Duration.ofSeconds(30);

    private final SessionRuntimeRegistry runtimeRegistry;
    private final WsConnectionRegistry connectionRegistry;
    private final SessionRepository sessionRepository;
    private final WsCommandParser commandParser;
    private final ClockProvider clock;
    private final SchedulerPort scheduler;
    private final ObjectMapper objectMapper;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public ClassroomWebSocketHandler(
            SessionRuntimeRegistry runtimeRegistry,
            WsConnectionRegistry connectionRegistry,
            SessionRepository sessionRepository,
            WsCommandParser commandParser,
            ClockProvider clock,
            SchedulerPort scheduler,
            ObjectMapper objectMapper) {
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.connectionRegistry = Objects.requireNonNull(
                connectionRegistry, "connectionRegistry");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.commandParser = Objects.requireNonNull(commandParser, "commandParser");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Connection connection = new Connection(session);
        connections.put(session.getId(), connection);
        session.setTextMessageSizeLimit(WsCommandParser.MAX_MESSAGE_BYTES);

        SessionId sessionId = (SessionId) session.getAttributes()
                .get(WsHandshakeInterceptor.ATTR_SESSION_ID);
        AccountId accountId = (AccountId) session.getAttributes()
                .get(WsHandshakeInterceptor.ATTR_ACCOUNT_ID);
        if (sessionId == null || accountId == null) {
            connection.close(CLOSE_AUTH, "认证失败");
            return;
        }

        SessionActor actor;
        try {
            actor = runtimeRegistry.getOrCreate(sessionId);
        } catch (DomainException exception) {
            connection.close(CLOSE_FAILED, "会话不可用");
            return;
        }
        if (!actor.session().studentAccountId().equals(accountId)) {
            connection.close(CLOSE_AUTH, "会话归属不匹配");
            return;
        }

        connection.bind(sessionId, actor);
        boolean terminal;
        try {
            // Hold the actor monitor across register + snapshot so no event can be pushed
            // between the snapshot's last_seq and the sink becoming active.
            synchronized (actor) {
                SessionStatus status = actor.session().status();
                if (status == SessionStatus.FAILED) {
                    connection.close(CLOSE_FAILED, "会话已失败");
                    return;
                }
                if (status == SessionStatus.CANCELLED
                        || status == SessionStatus.SUBMITTED) {
                    connection.close(CLOSE_STATE, "当前状态不允许连接");
                    return;
                }
                Consumer<SessionEvent> previous = connectionRegistry.register(
                        sessionId, connection.sink);
                actor.attachEventSink(new SessionEventSinkAdapter(
                        connectionRegistry, sessionId));
                SessionSnapshot snapshot = actor.snapshot();
                connection.lastSeq.set(snapshot.lastSeq());
                connection.sender.send(ServerEventEnvelope.snapshot(
                        sessionId, snapshot.lastSeq(), snapshot.elapsedMs(),
                        snapshotPayload(snapshot)));
                if (previous != null) {
                    closeReplaced(previous);
                }
                terminal = status == SessionStatus.ENDED
                        || status == SessionStatus.REVIEWING;
            }
        } catch (IOException | RuntimeException exception) {
            LOG.warn("Failed to establish control WebSocket for session {}",
                    sessionId.value(), exception);
            connection.close(CLOSE_FAILED, "连接初始化失败");
            return;
        }

        if (terminal) {
            connection.close(CloseStatus.NORMAL.getCode(), "会话已结束");
            return;
        }
        connection.scheduleHeartbeat();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection == null) {
            return;
        }
        if (commandParser.exceedsSizeLimit(message.getPayload())) {
            connection.close(CLOSE_TOO_LARGE, "消息超过 64 KB");
            return;
        }

        ClientCommandEnvelope envelope = null;
        try {
            envelope = commandParser.parse(message.getPayload());
            SessionActor actor = connection.actor;
            if (actor == null) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR, "连接未就绪");
            }
            if ("pong".equals(envelope.type())) {
                connection.lastPongAt.set(clock.now().toEpochMilli());
            }
            SessionCommand command = commandParser.toCommand(envelope);
            CommandResult result = actor.handle(command);
            if (result.ok()) {
                persistIfNeeded(actor, command);
                connection.sendAck(envelope.commandId());
            } else {
                connection.sendError(
                        result.errorCode(), result.message(), envelope.commandId());
            }
        } catch (DomainException exception) {
            connection.sendError(exception.code(), exception.getMessage(),
                    envelope == null ? null : envelope.commandId());
        } catch (RuntimeException exception) {
            LOG.error("Unexpected WebSocket command failure", exception);
            connection.sendError(ErrorCode.INTERNAL_ERROR, "命令处理失败",
                    envelope == null ? null : envelope.commandId());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Connection connection = connections.get(session.getId());
        if (connection != null && exception instanceof SessionLimitExceededException) {
            connection.close(CLOSE_TOO_LARGE, "消息过大或消费过慢");
        }
        // Other transport errors are followed by afterConnectionClosed, which starts grace.
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection connection = connections.remove(session.getId());
        if (connection == null) {
            return;
        }
        connection.closed.set(true);
        connection.cancelHeartbeat();
        if (connection.sessionId != null && connection.actor != null
                && connectionRegistry.remove(connection.sessionId, connection.sink)) {
            // Only the still-registered connection starts grace: a replaced socket and a sink
            // already removed by a transport failure must not schedule a second timer.
            connection.actor.onWsDisconnected(clock.now());
        }
    }

    private void persistIfNeeded(SessionActor actor, SessionCommand command) {
        if (command instanceof SessionCommand.Pong
                || command instanceof SessionCommand.TimeSync) {
            return;
        }
        try {
            sessionRepository.save(actor.session());
        } catch (RuntimeException exception) {
            LOG.error("Failed to persist session {} after WS command",
                    actor.session().id().value(), exception);
        }
    }

    private void closeReplaced(Consumer<SessionEvent> previous) {
        for (Connection candidate : connections.values()) {
            if (candidate.sink == previous) {
                candidate.close(CloseStatus.NORMAL.getCode(), "被新连接替换");
                return;
            }
        }
    }

    private static Map<String, Object> snapshotPayload(SessionSnapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", snapshot.status().wireValue());
        payload.put("mode", snapshot.mode());
        payload.put("public_config", snapshot.publicConfig());
        payload.put("virtual_students", snapshot.virtualStudents());
        payload.put("interview", snapshot.interview());
        payload.put("recording", snapshot.recording());
        payload.put("last_seq", snapshot.lastSeq());
        payload.put("elapsed_ms", snapshot.elapsedMs());
        return payload;
    }

    /**
     * Per-socket state. {@link #sink} identity is used to unregister only this connection and to
     * recognise the socket replaced by a newer connection.
     */
    private final class Connection {

        private final WebSocketSession session;
        private final WsMessageSender sender;
        private final Consumer<SessionEvent> sink;
        private final AtomicLong lastSeq = new AtomicLong();
        private final AtomicLong lastPongAt = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile SessionId sessionId;
        private volatile SessionActor actor;
        private volatile SchedulerPort.Cancellable heartbeat;

        private Connection(WebSocketSession session) {
            this.session = session;
            this.sender = new WsMessageSender(session, objectMapper);
            this.sink = this::sendEvent;
            this.lastPongAt.set(clock.now().toEpochMilli());
        }

        private void bind(SessionId sessionId, SessionActor actor) {
            this.sessionId = sessionId;
            this.actor = actor;
        }

        /** Single live push path: actor events become server envelopes. */
        private void sendEvent(SessionEvent event) {
            try {
                lastSeq.set(event.seq());
                sender.send(ServerEventEnvelope.of(event));
            } catch (IOException exception) {
                close(CLOSE_TOO_LARGE, "事件推送失败");
                throw new IllegalStateException("WebSocket 推送失败", exception);
            } catch (RuntimeException exception) {
                close(CLOSE_TOO_LARGE, "事件推送失败");
                throw exception;
            }
        }

        private void sendAck(String commandId) {
            try {
                sender.send(ServerEventEnvelope.ack(
                        sessionId, lastSeq.get(), sessionAtMs(actor), commandId, true));
            } catch (IOException | RuntimeException exception) {
                close(CLOSE_TOO_LARGE, "确认发送失败");
            }
        }

        private void sendError(ErrorCode code, String message, String commandId) {
            try {
                sender.send(ServerEventEnvelope.error(
                        sessionId, lastSeq.get(), sessionAtMs(actor),
                        commandId, code, message));
            } catch (IOException | RuntimeException exception) {
                close(CLOSE_TOO_LARGE, "错误发送失败");
            }
        }

        private void scheduleHeartbeat() {
            heartbeat = scheduler.schedule(this::heartbeatTick, PING_INTERVAL);
        }

        private void heartbeatTick() {
            if (closed.get() || !session.isOpen()
                    || sessionId == null || actor == null) {
                return;
            }
            long nowMs = clock.now().toEpochMilli();
            if (nowMs - lastPongAt.get() >= PONG_TIMEOUT.toMillis()) {
                close(CLOSE_HEARTBEAT, "心跳超时");
                return;
            }
            try {
                sender.send(ServerEventEnvelope.ping(
                        sessionId, lastSeq.get(), sessionAtMs(actor), nowMs));
            } catch (IOException | RuntimeException exception) {
                close(CLOSE_TOO_LARGE, "心跳发送失败");
                return;
            }
            heartbeat = scheduler.schedule(this::heartbeatTick, PING_INTERVAL);
        }

        private void close(int code, String reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            cancelHeartbeat();
            try {
                if (session.isOpen()) {
                    session.close(new CloseStatus(code, reason));
                }
            } catch (IOException ignored) {
                // The container will still invoke afterConnectionClosed.
            }
        }

        private void cancelHeartbeat() {
            SchedulerPort.Cancellable current = heartbeat;
            heartbeat = null;
            if (current != null) {
                current.cancel();
            }
        }
    }

    private long sessionAtMs(SessionActor actor) {
        if (actor == null) {
            return 0L;
        }
        synchronized (actor) {
            Instant startedAt = actor.session().startedAt();
            return startedAt == null
                    ? 0L
                    : Math.max(0L, Duration.between(startedAt, clock.now()).toMillis());
        }
    }
}
