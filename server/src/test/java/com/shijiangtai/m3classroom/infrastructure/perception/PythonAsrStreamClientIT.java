package com.shijiangtai.m3classroom.infrastructure.perception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.port.AsrChunk;
import com.shijiangtai.m3classroom.application.port.AsrRequest;
import com.shijiangtai.m3classroom.application.port.AsrSession;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.shared.domain.SessionId;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end test of the JDK WebSocket adapter against a real server port.
 *
 * <p>The test server speaks the Python ASR wire contract: one partial chunk per binary PCM frame,
 * then a final chunk plus {@code done} after the {@code finish} text command.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PythonAsrStreamClientIT.AsrTestServerConfig.class)
class PythonAsrStreamClientIT extends PostgresIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String SERVICE_TOKEN = "asr-test-token";

    @LocalServerPort
    private int port;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void resetServer() {
        AsrTestServerConfig.HANDLER.reset();
    }

    @Test
    void streamsPcmAndReportsChunks() throws Exception {
        SessionId sessionId = new SessionId(UUID.randomUUID());
        PythonAsrStreamClient client =
                new PythonAsrStreamClient(properties(port, null), mapper);
        AsrSession session = client.open(new AsrRequest(sessionId, 16000, "pcm", "zh"));
        try {
            LinkedBlockingQueue<AsrChunk> chunks = new LinkedBlockingQueue<>();
            List<RuntimeException> errors = new CopyOnWriteArrayList<>();
            CountDownLatch closed = new CountDownLatch(1);
            session.onChunk(chunks::add);
            session.onError(errors::add);
            session.onClosed(closed::countDown);

            session.send(new byte[] {1, 2, 3});
            session.send(new byte[] {4, 5, 6});
            session.finish();

            assertThat(closed.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .as("closed listener must fire after the server sends done")
                    .isTrue();

            List<AsrChunk> received = new ArrayList<>();
            chunks.drainTo(received);
            assertThat(received).hasSize(3);

            List<AsrChunk> partials =
                    received.stream().filter(chunk -> !chunk.finalChunk()).toList();
            assertThat(partials).hasSize(2);
            assertThat(partials).extracting(AsrChunk::text)
                    .containsExactlyInAnyOrder("片段1", "片段2");
            assertThat(partials).allSatisfy(chunk -> {
                assertThat(chunk.beginMs()).isNotNull();
                assertThat(chunk.endMs()).isNotNull();
                assertThat(chunk.confidence()).isNotNull();
            });

            AsrChunk finalChunk = received.stream()
                    .filter(AsrChunk::finalChunk)
                    .findFirst()
                    .orElseThrow();
            assertThat(finalChunk.text()).isEqualTo("这是最后一句");
            assertThat(finalChunk.beginMs()).isEqualTo(0L);
            assertThat(finalChunk.endMs()).isEqualTo(1500L);
            assertThat(finalChunk.confidence()).isEqualByComparingTo("0.9");
            assertThat(errors).isEmpty();

            AsrTestHandler handler = AsrTestServerConfig.HANDLER;
            assertThat(handler.binaryFrames()).isEqualTo(2);
            assertThat(handler.finishCommands()).isEqualTo(1);
            assertThat(handler.queryParams())
                    .containsEntry("session_id", sessionId.value().toString())
                    .containsEntry("sample_rate", "16000")
                    .containsEntry("format", "pcm")
                    .containsEntry("language", "zh");
            assertThat(handler.serviceToken()).isEqualTo(SERVICE_TOKEN);
            assertThat(handler.binaryPayloadLengths()).containsExactly(3, 3);
        } finally {
            session.close();
        }
    }

    @Test
    void openAgainstClosedPortThrowsWithinConnectTimeout() throws Exception {
        int closedPort = closedPort();
        PythonAsrStreamClient client = new PythonAsrStreamClient(
                properties(closedPort, Duration.ofMillis(500)), mapper);
        AsrRequest request =
                new AsrRequest(new SessionId(UUID.randomUUID()), 16000, "pcm", "zh");

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.open(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("ASR 服务不可用");
        long elapsedMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMillis).isLessThan(5_000L);
    }

    private PerceptionAiProperties properties(int targetPort, Duration connectTimeout) {
        return new PerceptionAiProperties(
                "http://localhost:" + targetPort, SERVICE_TOKEN, null, null, connectTimeout);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @TestConfiguration
    static class AsrTestServerConfig implements WebSocketConfigurer {

        static final AsrTestHandler HANDLER = new AsrTestHandler();

        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(HANDLER, "/internal/v1/asr/stream").setAllowedOrigins("*");
        }
    }

    /** Minimal Python-ASR stand-in: partial per PCM frame, final + done after finish. */
    static final class AsrTestHandler extends AbstractWebSocketHandler {

        private final AtomicInteger binaryFrames = new AtomicInteger();
        private final AtomicInteger finishCommands = new AtomicInteger();
        private final List<Integer> binaryPayloadLengths = new CopyOnWriteArrayList<>();
        private volatile Map<String, String> queryParams = Map.of();
        private volatile String serviceToken;

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            queryParams = parseQuery(session.getUri());
            serviceToken = session.getHandshakeHeaders().getFirst("X-Service-Token");
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message)
                throws Exception {
            int index = binaryFrames.incrementAndGet();
            binaryPayloadLengths.add(message.getPayloadLength());
            session.sendMessage(new TextMessage(
                    "{\"type\":\"chunk\",\"text\":\"片段" + index + "\",\"is_final\":false,"
                            + "\"begin_ms\":" + (index - 1) * 400 + ",\"end_ms\":" + index * 400
                            + ",\"confidence\":0.8}"));
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message)
                throws Exception {
            if (!"{\"type\":\"finish\"}".equals(message.getPayload())) {
                return;
            }
            finishCommands.incrementAndGet();
            session.sendMessage(new TextMessage(
                    "{\"type\":\"chunk\",\"text\":\"这是最后一句\",\"is_final\":true,"
                            + "\"begin_ms\":0,\"end_ms\":1500,\"confidence\":0.9}"));
            session.sendMessage(new TextMessage("{\"type\":\"done\"}"));
            session.close(CloseStatus.NORMAL);
        }

        int binaryFrames() {
            return binaryFrames.get();
        }

        int finishCommands() {
            return finishCommands.get();
        }

        List<Integer> binaryPayloadLengths() {
            return List.copyOf(binaryPayloadLengths);
        }

        Map<String, String> queryParams() {
            return queryParams;
        }

        String serviceToken() {
            return serviceToken;
        }

        void reset() {
            binaryFrames.set(0);
            finishCommands.set(0);
            binaryPayloadLengths.clear();
            queryParams = Map.of();
            serviceToken = null;
        }

        private static Map<String, String> parseQuery(URI uri) {
            if (uri == null || uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
                return Map.of();
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (String pair : uri.getRawQuery().split("&")) {
                int separator = pair.indexOf('=');
                String name = separator < 0 ? pair : pair.substring(0, separator);
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                params.put(
                        URLDecoder.decode(name, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
            return params;
        }
    }
}
