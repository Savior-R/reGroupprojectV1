package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.port.AsrChunk;
import com.shijiangtai.m3classroom.application.port.AsrRequest;
import com.shijiangtai.m3classroom.application.port.AsrSession;
import com.shijiangtai.m3classroom.application.port.AsrStreamClient;
import com.shijiangtai.m3classroom.application.port.ClassifyResult;
import com.shijiangtai.m3classroom.application.port.SchedulerPort;
import com.shijiangtai.m3classroom.application.port.TeacherSpeechClassifier;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Raw perception WebSocket for {@code /ws/v1/sessions/{sessionId}/perception?ticket=...}
 * (05A §6.2).
 *
 * <p>The client sends one {@code audio.start} text frame, binary PCM16 mono 16 kHz frames and one
 * {@code audio.stop} text frame. Chunks from the streaming-ASR session are forwarded as
 * {@code asr.partial}/{@code asr.final}; final teacher/candidate text is persisted through
 * {@link SessionActor#recordSpeechFinal}. Classroom finals are classified off-actor on a
 * per-connection daemon executor and a high-confidence question triggers the normal voice-QA
 * path.
 *
 * <p>AI failures are degraded, never propagated to the session: an ASR open failure keeps the
 * perception socket open (audio discarded) until {@code audio.stop}, a mid-stream failure is
 * reported as {@code error SERVICE_UNAVAILABLE}, and classification exceptions only log WARN.
 * Protocol violations send {@code error VALIDATION_ERROR} and close 4003; authentication and
 * session-state failures close 4001/4002/4003 before any ASR work starts.
 */
@Component
public final class PerceptionWebSocketHandler extends AbstractWebSocketHandler {

    static final int CLOSE_AUTH = 4001;
    static final int CLOSE_FAILED = 4002;
    static final int CLOSE_STATE = 4003;
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(2);

    private static final Logger LOG =
            LoggerFactory.getLogger(PerceptionWebSocketHandler.class);
    private static final String ASR_FORMAT = "pcm";
    private static final String ASR_LANGUAGE = "zh";
    private static final String SPEAKER_TEACHER = "teacher";
    private static final String SPEAKER_CANDIDATE = "candidate";
    private static final String QUESTION_LABEL = "question";

    private final SessionRuntimeRegistry runtimeRegistry;
    private final PerceptionConnectionRegistry connectionRegistry;
    private final SessionRepository sessionRepository;
    private final AsrStreamClient asrStreamClient;
    private final TeacherSpeechClassifier speechClassifier;
    private final SchedulerPort scheduler;
    private final ObjectMapper objectMapper;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public PerceptionWebSocketHandler(
            SessionRuntimeRegistry runtimeRegistry,
            PerceptionConnectionRegistry connectionRegistry,
            SessionRepository sessionRepository,
            AsrStreamClient asrStreamClient,
            TeacherSpeechClassifier speechClassifier,
            SchedulerPort scheduler,
            ObjectMapper objectMapper) {
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.connectionRegistry = Objects.requireNonNull(
                connectionRegistry, "connectionRegistry");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.asrStreamClient = Objects.requireNonNull(asrStreamClient, "asrStreamClient");
        this.speechClassifier = Objects.requireNonNull(speechClassifier, "speechClassifier");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        Connection connection = new Connection(socket);
        connections.put(socket.getId(), connection);
        // Reach the handler so an oversized binary frame is not rejected by the container with
        // an unrelated 1009 close before the protocol layer can answer.
        socket.setBinaryMessageSizeLimit(WebSocketConfig.MAX_FRAME_BYTES);

        String authError = (String) socket.getAttributes()
                .get(WsHandshakeInterceptor.ATTR_AUTH_ERROR);
        if (authError != null) {
            connection.close(closeCodeForAuthError(authError), "认证或会话状态不允许连接");
            return;
        }

        SessionId sessionId = (SessionId) socket.getAttributes()
                .get(WsHandshakeInterceptor.ATTR_SESSION_ID);
        AccountId accountId = (AccountId) socket.getAttributes()
                .get(WsHandshakeInterceptor.ATTR_ACCOUNT_ID);
        if (sessionId == null || accountId == null) {
            connection.close(CLOSE_AUTH, "认证失败");
            return;
        }

        Session persisted;
        try {
            persisted = sessionRepository.findById(sessionId).orElse(null);
        } catch (RuntimeException exception) {
            LOG.warn("Failed to load session {} for perception WebSocket: {}",
                    sessionId.value(), exception.toString());
            connection.close(CLOSE_FAILED, "会话不可用");
            return;
        }
        if (persisted == null) {
            connection.close(CLOSE_FAILED, "会话不可用");
            return;
        }
        if (isTerminal(persisted.status())) {
            connection.close(CLOSE_FAILED, "会话已结束");
            return;
        }
        if (persisted.status() == SessionStatus.PREPARING) {
            connection.close(CLOSE_STATE, "当前状态不允许连接");
            return;
        }
        if (!persisted.studentAccountId().equals(accountId)) {
            connection.close(CLOSE_AUTH, "会话归属不匹配");
            return;
        }

        SessionActor actor;
        try {
            actor = runtimeRegistry.getOrCreate(sessionId);
        } catch (RuntimeException exception) {
            LOG.warn("Failed to build actor for perception session {}: {}",
                    sessionId.value(), exception.toString());
            connection.close(CLOSE_FAILED, "会话不可用");
            return;
        }

        try {
            // Hold the actor monitor across the status check and listener registration so a
            // terminal transition can neither slip between them nor happen before the listener
            // exists. The registry then owns replacement and terminal-close semantics.
            synchronized (actor) {
                SessionStatus status = actor.session().status();
                if (status == SessionStatus.PREPARING) {
                    connection.close(CLOSE_STATE, "当前状态不允许连接");
                    return;
                }
                if (status != SessionStatus.IN_PROGRESS) {
                    // A cached terminal actor is still a shield when the terminal save failed;
                    // evict only when the persisted row is terminal too.
                    runtimeRegistry.removeIfPersistedTerminal(sessionId);
                    connection.close(CLOSE_FAILED, "会话已结束");
                    return;
                }
                connection.bind(sessionId, actor);
                connectionRegistry.register(sessionId, socket, actor);
            }
        } catch (RuntimeException exception) {
            LOG.warn("Failed to register perception connection for session {}: {}",
                    sessionId.value(), exception.toString());
            connection.close(CLOSE_FAILED, "连接初始化失败");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        Connection connection = connections.get(socket.getId());
        if (connection == null || connection.closed.get()) {
            return;
        }
        PerceptionProtocolParser.Message parsed;
        try {
            parsed = PerceptionProtocolParser.parse(message.getPayload());
        } catch (DomainException exception) {
            connection.sendProtocolError(exception.getMessage());
            return;
        }
        if (parsed instanceof PerceptionProtocolParser.AudioStart start) {
            connection.startAudio(start);
        } else {
            connection.stopAudio();
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        Connection connection = connections.get(socket.getId());
        if (connection == null) {
            return;
        }
        connection.acceptAudio(message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable exception) {
        LOG.warn("Perception WebSocket transport error for socket {}: {}",
                socket.getId(), exception.toString());
        // A transport error is followed by afterConnectionClosed, which releases ASR and the
        // classifier executor.
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Connection connection = connections.remove(socket.getId());
        if (connection != null) {
            connection.cleanup();
        }
    }

    private static int closeCodeForAuthError(String authError) {
        return switch (authError) {
            case WsHandshakeInterceptor.AUTH_FAILED -> CLOSE_FAILED;
            case WsHandshakeInterceptor.AUTH_STATE -> CLOSE_STATE;
            default -> CLOSE_AUTH;
        };
    }

    private static boolean isTerminal(SessionStatus status) {
        return status == SessionStatus.ENDED
                || status == SessionStatus.REVIEWING
                || status == SessionStatus.SUBMITTED
                || status == SessionStatus.CANCELLED
                || status == SessionStatus.FAILED;
    }

    // ------------------------------------------------------------------
    // Per-connection state
    // ------------------------------------------------------------------

    private final class Connection {

        private final WebSocketSession socket;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final AtomicBoolean audioStarted = new AtomicBoolean();
        private final AtomicBoolean audioStopped = new AtomicBoolean();
        private final ExecutorService classifierExecutor;
        private final Object asrLock = new Object();
        private final AtomicReference<SchedulerPort.Cancellable> drainTimeout =
                new AtomicReference<>();
        private volatile SessionId sessionId;
        private volatile SessionActor actor;
        private volatile AsrSession asr;
        private volatile long audioStartAtMs;

        private Connection(WebSocketSession socket) {
            this.socket = socket;
            this.classifierExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable,
                        "perception-classify-" + socket.getId());
                thread.setDaemon(true);
                return thread;
            });
        }

        private void bind(SessionId sessionId, SessionActor actor) {
            this.sessionId = sessionId;
            this.actor = actor;
        }

        // --------------------------------------------------------------
        // Client messages
        // --------------------------------------------------------------

        private void startAudio(PerceptionProtocolParser.AudioStart start) {
            if (!audioStarted.compareAndSet(false, true)) {
                sendProtocolError("重复的 audio.start");
                return;
            }
            if (!actor.isInProgress()) {
                sendProtocolError("当前会话状态不允许开始音频");
                return;
            }
            // Timestamps are relative to the moment the client reported that audio begins, not
            // to the end of the (possibly slow) ASR handshake.
            audioStartAtMs = actor.currentAtMs();

            AsrSession opened;
            try {
                opened = asrStreamClient.open(new AsrRequest(
                        sessionId, start.sampleRate(), ASR_FORMAT, ASR_LANGUAGE));
            } catch (RuntimeException exception) {
                LOG.warn("Failed to open ASR for session {}: {}",
                        sessionId.value(), exception.toString());
                sendError(ErrorCode.SERVICE_UNAVAILABLE, "ASR 服务不可用");
                return;
            }
            opened.onChunk(this::handleChunk);
            opened.onError(this::handleAsrError);
            opened.onClosed(this::handleAsrClosed);
            synchronized (asrLock) {
                if (closed.get()) {
                    // The socket closed while the ASR handshake was in flight: release the
                    // session instead of leaking it (cleanup already ran and saw no ASR).
                    closeQuietly(opened);
                    return;
                }
                asr = opened;
            }
        }

        private void acceptAudio(ByteBuffer payload) {
            if (closed.get()) {
                return;
            }
            if (!audioStarted.get()) {
                sendProtocolError("audio.start 之前不允许发送音频数据");
                return;
            }
            if (audioStopped.get()) {
                return;
            }
            AsrSession current = asr;
            if (current == null) {
                // Open failed earlier: audio is deliberately discarded until audio.stop.
                return;
            }
            byte[] pcm = new byte[payload.remaining()];
            payload.get(pcm);
            try {
                current.send(pcm);
            } catch (RuntimeException exception) {
                LOG.warn("Failed to forward PCM for session {}: {}",
                        sessionId.value(), exception.toString());
            }
        }

        private void stopAudio() {
            if (closed.get()) {
                return;
            }
            if (!audioStarted.get()) {
                sendProtocolError("audio.stop 之前必须 audio.start");
                return;
            }
            if (!audioStopped.compareAndSet(false, true)) {
                return;
            }
            AsrSession current = asr;
            if (current == null) {
                // ASR never opened: no drain is possible, close normally.
                close(CloseStatus.NORMAL.getCode(), "音频结束");
                return;
            }
            try {
                current.finish();
            } catch (RuntimeException exception) {
                LOG.warn("Failed to finish ASR for session {}: {}",
                        sessionId.value(), exception.toString());
            }
            if (!closed.get()) {
                scheduleDrainTimeout();
            }
        }

        // --------------------------------------------------------------
        // ASR callbacks (arrive on another thread)
        // --------------------------------------------------------------

        private void handleChunk(AsrChunk chunk) {
            if (chunk == null || closed.get()) {
                return;
            }
            try {
                if (chunk.finalChunk()) {
                    handleFinal(chunk);
                } else {
                    sendPartial(chunk);
                }
            } catch (RuntimeException exception) {
                LOG.warn("Failed to handle ASR chunk for session {}: {}",
                        sessionId.value(), exception.toString());
            }
        }

        private void handleFinal(AsrChunk chunk) {
            String text = chunk.text();
            if (text == null || text.isBlank()) {
                return;
            }
            boolean interview = actor.session().mode() == TaskMode.INTERVIEW;
            String speakerType = interview ? SPEAKER_CANDIDATE : SPEAKER_TEACHER;
            UUID speakerRef = interview ? actor.currentAnswerId() : null;

            Long startMs = chunk.beginMs() == null
                    ? null
                    : Math.max(0L, audioStartAtMs + chunk.beginMs());
            Long endMs = chunk.endMs() != null
                    ? Math.max(0L, audioStartAtMs + chunk.endMs())
                    : Math.max(0L, actor.currentAtMs());

            Optional<UUID> textId = actor.recordSpeechFinal(
                    speakerType, speakerRef, text, startMs, endMs, chunk.confidence());
            if (textId.isEmpty()) {
                // Late final after the session became terminal: drop it without a response.
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "asr.final");
            payload.put("text", text);
            payload.put("text_id", textId.get().toString());
            payload.put("at_ms", endMs);
            send(payload);

            if (!interview) {
                submitClassification(text);
            }
        }

        private void sendPartial(AsrChunk chunk) {
            long endMs = chunk.endMs() == null ? 0L : chunk.endMs();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "asr.partial");
            payload.put("text", chunk.text() == null ? "" : chunk.text());
            payload.put("at_ms", Math.max(0L, audioStartAtMs + endMs));
            send(payload);
        }

        private void submitClassification(String text) {
            String mode = actor.session().mode().wireValue();
            try {
                classifierExecutor.execute(() -> {
                    try {
                        ClassifyResult result =
                                speechClassifier.classify(text, List.of(), mode);
                        if (result != null
                                && QUESTION_LABEL.equalsIgnoreCase(result.label())
                                && result.confidence() != null) {
                            actor.onTeacherSpeechClassified(
                                    result.confidence().doubleValue());
                        }
                    } catch (RuntimeException exception) {
                        LOG.warn("Teacher speech classification failed for session {}: {}",
                                sessionId.value(), exception.toString());
                    }
                });
            } catch (RejectedExecutionException exception) {
                // The connection is closing and the executor has been shut down: drop the work.
                LOG.debug("Classification rejected for closing session {}",
                        sessionId.value());
            }
        }

        private void handleAsrError(RuntimeException error) {
            if (closed.get()) {
                return;
            }
            LOG.warn("ASR error for session {}: {}", sessionId.value(),
                    error == null ? "unknown" : error.toString());
            sendError(ErrorCode.SERVICE_UNAVAILABLE, "ASR 服务不可用");
        }

        private void handleAsrClosed() {
            if (closed.get()) {
                return;
            }
            if (audioStopped.get()) {
                close(CloseStatus.NORMAL.getCode(), "音频结束");
                return;
            }
            LOG.warn("ASR closed unexpectedly for session {}", sessionId.value());
            sendError(ErrorCode.SERVICE_UNAVAILABLE, "ASR 服务连接中断");
        }

        // --------------------------------------------------------------
        // Sending and closing
        // --------------------------------------------------------------

        private void sendProtocolError(String message) {
            sendError(ErrorCode.VALIDATION_ERROR, message);
            close(CLOSE_STATE, "协议错误");
        }

        private void sendError(ErrorCode code, String message) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "error");
            payload.put("code", code.name());
            payload.put("message", message == null ? "" : message);
            send(payload);
        }

        private void send(Map<String, Object> payload) {
            String json;
            try {
                json = objectMapper.writeValueAsString(payload);
            } catch (RuntimeException exception) {
                LOG.warn("Failed to serialize perception message: {}", exception.toString());
                return;
            }
            try {
                // ASR callbacks, the classifier callback and the container thread may all write;
                // every send is serialized on the socket to prevent interleaved frames.
                synchronized (socket) {
                    if (!socket.isOpen()) {
                        return;
                    }
                    socket.sendMessage(new TextMessage(json));
                }
            } catch (IOException exception) {
                // The socket closed; the container will invoke afterConnectionClosed.
                LOG.debug("Perception socket closed before message could be sent: {}",
                        exception.toString());
            }
        }

        private void close(int code, String reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            cancelDrain();
            try {
                synchronized (socket) {
                    if (socket.isOpen()) {
                        socket.close(new CloseStatus(code, reason));
                    }
                }
            } catch (IOException exception) {
                // The container still invokes afterConnectionClosed for cleanup.
                LOG.debug("Failed to close perception socket: {}", exception.toString());
            }
        }

        private void scheduleDrainTimeout() {
            cancelDrain();
            try {
                drainTimeout.set(scheduler.schedule(
                        () -> close(CloseStatus.NORMAL.getCode(), "音频结束"),
                        DRAIN_TIMEOUT));
            } catch (RuntimeException exception) {
                LOG.warn("Failed to schedule ASR drain timeout for session {}: {}",
                        sessionId.value(), exception.toString());
                close(CloseStatus.NORMAL.getCode(), "音频结束");
            }
        }

        private void cancelDrain() {
            SchedulerPort.Cancellable current = drainTimeout.getAndSet(null);
            if (current != null) {
                current.cancel();
            }
        }

        private void cleanup() {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            cancelDrain();
            SessionId boundSession = sessionId;
            if (boundSession != null) {
                connectionRegistry.unregister(boundSession, socket);
            }
            AsrSession current;
            synchronized (asrLock) {
                closed.set(true);
                current = asr;
                asr = null;
            }
            if (current != null) {
                closeQuietly(current);
            }
            // Queued classifications finish (the actor drops them after a terminal status).
            classifierExecutor.shutdown();
        }

        private void closeQuietly(AsrSession session) {
            try {
                session.close();
            } catch (RuntimeException exception) {
                LOG.debug("Failed to close ASR session: {}", exception.toString());
            }
        }
    }

}
