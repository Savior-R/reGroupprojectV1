package com.shijiangtai.m3classroom.infrastructure.perception;

import com.shijiangtai.m3classroom.application.port.AsrChunk;
import com.shijiangtai.m3classroom.application.port.AsrRequest;
import com.shijiangtai.m3classroom.application.port.AsrSession;
import com.shijiangtai.m3classroom.application.port.AsrStreamClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JDK WebSocket adapter to the Python streaming ASR service.
 *
 * <p>Wire contract: PCM is sent as binary frames; after the last frame the client sends the text
 * command {@code {"type":"finish"}}. The service answers with {@code chunk} messages, then
 * {@code done}, or an {@code error} message (usually followed by a 1011 close).
 *
 * <p>The listener is registered before the handshake completes. Chunks that arrive before the
 * consumer sets {@link AsrSession#onChunk(Consumer)} are buffered and replayed when it does, so
 * registration order cannot lose speech.
 */
@Component
@ConditionalOnProperty(name = "m3.ai.base-url")
public class PythonAsrStreamClient implements AsrStreamClient {

    static final int OUTBOUND_CAPACITY = 64;
    static final String UNAVAILABLE = "ASR 服务不可用";

    private static final String STREAM_PATH = "/internal/v1/asr/stream";
    private static final String FINISH_COMMAND = "{\"type\":\"finish\"}";
    private static final byte[] FINISH_SENTINEL = new byte[0];

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PerceptionAiProperties properties;
    private final String webSocketBaseUrl;

    public PythonAsrStreamClient(
            PerceptionAiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webSocketBaseUrl = toWebSocketBase(properties.baseUrl());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.asrConnectTimeout())
                .build();
    }

    @Override
    public AsrSession open(AsrRequest request) {
        Objects.requireNonNull(request, "request");
        Duration timeout = properties.asrConnectTimeout();
        Session session = new Session(objectMapper);
        CompletableFuture<WebSocket> handshake = null;
        try {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                    .connectTimeout(timeout);
            String serviceToken = properties.serviceToken();
            if (serviceToken != null && !serviceToken.isBlank()) {
                builder.header("X-Service-Token", serviceToken);
            }
            handshake = builder.buildAsync(streamUri(request), session);
            WebSocket webSocket = handshake.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            session.attach(webSocket);
            return session;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            abortHandshake(handshake, session);
            throw new RuntimeException(UNAVAILABLE, exception);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            abortHandshake(handshake, session);
            throw new RuntimeException(UNAVAILABLE, exception);
        }
    }

    private static void abortHandshake(
            CompletableFuture<WebSocket> handshake, Session session) {
        if (handshake != null) {
            handshake.whenComplete((webSocket, error) -> {
                if (webSocket != null) {
                    webSocket.abort();
                }
            });
        }
        session.abort();
    }

    private URI streamUri(AsrRequest request) {
        String query = "session_id="
                + encode(request.sessionId().value().toString())
                + "&sample_rate=" + request.sampleRate()
                + "&format=" + encode(request.format())
                + "&language=" + encode(request.language());
        return URI.create(webSocketBaseUrl + STREAM_PATH + "?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String toWebSocketBase(String baseUrl) {
        String trimmed = stripTrailingSlash(baseUrl == null ? "" : baseUrl.trim());
        if (trimmed.startsWith("https://")) {
            return "wss://" + trimmed.substring("https://".length());
        }
        if (trimmed.startsWith("http://")) {
            return "ws://" + trimmed.substring("http://".length());
        }
        return trimmed;
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private final class Session implements AsrSession, WebSocket.Listener {

        private final ObjectMapper mapper;
        private final ArrayBlockingQueue<byte[]> outbound =
                new ArrayBlockingQueue<>(OUTBOUND_CAPACITY);
        private final Object listenerLock = new Object();
        private final List<AsrChunk> pendingChunks = new ArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean finishRequested = new AtomicBoolean();
        private final StringBuilder textBuffer = new StringBuilder();

        private volatile WebSocket webSocket;
        private volatile Thread sender;
        private volatile Consumer<AsrChunk> chunkListener;
        private volatile Runnable closedListener;
        private volatile Consumer<RuntimeException> errorListener;
        private RuntimeException pendingError;
        private boolean closedSignalled;
        private boolean closedDelivered;
        private boolean errorDelivered;

        private Session(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        private void attach(WebSocket socket) {
            if (closed.get()) {
                socket.abort();
                return;
            }
            webSocket = socket;
            if (closed.get()) {
                socket.abort();
                return;
            }
            Thread thread = new Thread(() -> sendLoop(socket), "asr-sender");
            thread.setDaemon(true);
            sender = thread;
            thread.start();
        }

        private void abort() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Thread thread = sender;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
            WebSocket socket = webSocket;
            if (socket != null) {
                socket.abort();
            }
        }

        @Override
        public void send(byte[] pcm) {
            Objects.requireNonNull(pcm, "pcm");
            if (closed.get() || finishRequested.get()) {
                return;
            }
            if (pcm.length == 0) {
                finish();
                return;
            }
            if (!outbound.offer(pcm.clone())) {
                failQueueFull();
            }
        }

        @Override
        public void finish() {
            if (closed.get() || !finishRequested.compareAndSet(false, true)) {
                return;
            }
            if (!outbound.offer(FINISH_SENTINEL)) {
                failQueueFull();
            }
        }

        private void failQueueFull() {
            signalError(new RuntimeException("ASR 音频发送队列已满"));
            close();
        }

        @Override
        public void onChunk(Consumer<AsrChunk> listener) {
            Objects.requireNonNull(listener, "listener");
            synchronized (listenerLock) {
                if (closed.get()) {
                    return;
                }
                chunkListener = listener;
                List<AsrChunk> replay = List.copyOf(pendingChunks);
                pendingChunks.clear();
                for (AsrChunk chunk : replay) {
                    listener.accept(chunk);
                }
            }
        }

        @Override
        public void onClosed(Runnable listener) {
            Objects.requireNonNull(listener, "listener");
            boolean fire = false;
            synchronized (listenerLock) {
                if (closed.get()) {
                    return;
                }
                closedListener = listener;
                if (closedSignalled && !closedDelivered) {
                    closedDelivered = true;
                    fire = true;
                }
            }
            if (fire) {
                listener.run();
            }
        }

        @Override
        public void onError(Consumer<RuntimeException> listener) {
            Objects.requireNonNull(listener, "listener");
            RuntimeException error = null;
            synchronized (listenerLock) {
                if (closed.get()) {
                    return;
                }
                errorListener = listener;
                if (pendingError != null && !errorDelivered) {
                    errorDelivered = true;
                    error = pendingError;
                }
            }
            if (error != null) {
                listener.accept(error);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Thread thread = sender;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
            WebSocket socket = webSocket;
            if (socket != null) {
                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "client closed")
                            .whenComplete((ignored, error) -> {
                                if (error != null) {
                                    socket.abort();
                                }
                            });
                } catch (RuntimeException ignored) {
                    socket.abort();
                }
            }
        }

        private void sendLoop(WebSocket socket) {
            try {
                while (!closed.get()) {
                    byte[] pcm = outbound.take();
                    if (pcm.length == 0) {
                        socket.sendText(FINISH_COMMAND, true).join();
                        return;
                    }
                    socket.sendBinary(ByteBuffer.wrap(pcm), true).join();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException exception) {
                if (!closed.get()) {
                    signalError(new RuntimeException("ASR 发送失败", exception));
                    close();
                }
            }
        }

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(
                WebSocket socket, CharSequence data, boolean last) {
            if (closed.get()) {
                return CompletableFuture.completedFuture(null);
            }
            textBuffer.append(data);
            if (last) {
                String payload = textBuffer.toString();
                textBuffer.setLength(0);
                handleMessage(payload);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onBinary(
                WebSocket socket, ByteBuffer data, boolean last) {
            // The Python ASR service only sends text frames; binary responses are ignored.
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onClose(
                WebSocket socket, int statusCode, String reason) {
            signalClosed();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            signalError(new RuntimeException("ASR 连接异常", error));
        }

        private void handleMessage(String payload) {
            JsonNode node;
            try {
                node = mapper.readTree(payload);
            } catch (RuntimeException exception) {
                signalError(new RuntimeException("ASR 响应解析失败", exception));
                return;
            }
            if (node == null || !node.isObject()) {
                signalError(new RuntimeException("ASR 响应解析失败"));
                return;
            }
            String type = textOrEmpty(node.get("type"));
            switch (type) {
                case "chunk" -> {
                    try {
                        signalChunk(mapChunk(node));
                    } catch (RuntimeException exception) {
                        signalError(new RuntimeException("ASR 响应解析失败", exception));
                    }
                }
                case "done" -> signalClosed();
                case "error" -> signalError(
                        new RuntimeException(textOrEmpty(node.get("message"))));
                default -> {
                    // Forward-compatible: unknown message types are ignored.
                }
            }
        }

        private AsrChunk mapChunk(JsonNode node) {
            return new AsrChunk(
                    textOrEmpty(node.get("text")),
                    booleanOrFalse(node.get("is_final")),
                    longOrNull(node.get("begin_ms")),
                    longOrNull(node.get("end_ms")),
                    decimalOrNull(node.get("confidence")));
        }

        private void signalChunk(AsrChunk chunk) {
            synchronized (listenerLock) {
                if (closed.get()) {
                    return;
                }
                if (chunkListener == null) {
                    pendingChunks.add(chunk);
                    return;
                }
                chunkListener.accept(chunk);
            }
        }

        private void signalClosed() {
            Runnable listener = null;
            synchronized (listenerLock) {
                if (closed.get() || closedSignalled) {
                    return;
                }
                closedSignalled = true;
                if (closedListener != null && !closedDelivered) {
                    closedDelivered = true;
                    listener = closedListener;
                }
            }
            if (listener != null) {
                listener.run();
            }
        }

        private void signalError(RuntimeException error) {
            Consumer<RuntimeException> listener = null;
            synchronized (listenerLock) {
                if (closed.get()) {
                    return;
                }
                if (pendingError == null) {
                    pendingError = error;
                }
                if (errorListener != null && !errorDelivered) {
                    errorDelivered = true;
                    listener = errorListener;
                }
            }
            if (listener != null) {
                listener.accept(error);
            }
        }
    }

    private static String textOrEmpty(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asString();
    }

    private static boolean booleanOrFalse(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            throw new IllegalStateException("is_final must be a boolean");
        }
        return node.asBoolean();
    }

    private static Long longOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new IllegalStateException("timestamp must be numeric");
        }
        return node.asLong();
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new IllegalStateException("confidence must be numeric");
        }
        return node.decimalValue();
    }
}
