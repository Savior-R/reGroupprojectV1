package com.shijiangtai.m3classroom.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.application.SessionApplicationService;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.view.SessionView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Heartbeat timing against a real Tomcat port (02 §6.5): a client that never answers {@code ping}
 * is closed with 4004, while a client that answers stays connected. The shortened intervals are
 * injected through the handler's {@code m3.ws.*} properties so the test stays fast.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "m3.ws.ping-interval=200ms",
        "m3.ws.pong-timeout=500ms"
})
class WsHeartbeatIT extends PostgresIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int CLOSE_HEARTBEAT = 4004;

    @LocalServerPort
    private int port;

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskMaterialService materialService;

    @Autowired
    private TaskPublishService publishService;

    @Autowired
    private SessionApplicationService sessionApplicationService;

    @Autowired
    private SessionRuntimeRegistry runtimeRegistry;

    @Autowired
    private ClockProvider clock;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private TaskId taskId;
    private SessionId sessionId;

    @BeforeEach
    void publishTask() {
        TaskView view = taskApplicationService.createDraft(
                TestFixtures.teacher(), TestFixtures.freeCommandWithStudent());
        taskId = new TaskId(view.taskId());
        materialService.upload(TestFixtures.teacher(), taskId, "a.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于 WebSocket 心跳测试。"
                        .getBytes(StandardCharsets.UTF_8));
        publishService.publish(TestFixtures.teacher(), taskId);
    }

    @AfterEach
    void cleanUp() {
        if (sessionId != null) {
            runtimeRegistry.find(sessionId).ifPresent(actor -> {
                try {
                    // Cancelling also removes the grace timer armed by the heartbeat close.
                    actor.cancel(clock.now());
                } catch (RuntimeException ignored) {
                    // the row is truncated by @Sql before the next test anyway
                }
                runtimeRegistry.remove(sessionId);
            });
            sessionId = null;
        }
    }

    @Test
    void missingPongCloses4004WithinPongTimeout() throws Exception {
        SessionId id = createSession();
        HeartbeatListener listener = new HeartbeatListener(false);
        connect(id, listener);

        // 200 ms ping + 500 ms pong timeout: the close must land well inside this 3 s bound.
        int closeCode = listener.closeCode.get(3, TimeUnit.SECONDS);

        assertThat(closeCode).isEqualTo(CLOSE_HEARTBEAT);
        assertThat(listener.pingCount.get()).isPositive();
    }

    @Test
    void pongResponseKeepsConnectionOpen() throws Exception {
        SessionId id = createSession();
        HeartbeatListener listener = new HeartbeatListener(true);
        WebSocket socket = connect(id, listener);

        waitUntil(() -> listener.pingCount.get() >= 2, Duration.ofSeconds(3));
        // Wait beyond the 500 ms pong timeout; a missing/reset pong would close 4004 here.
        Thread.sleep(800L);
        assertThat(listener.closeCode.isDone()).isFalse();

        socket.sendText("{\"v\":1,\"type\":\"time.sync\",\"command_id\":\"alive-1\","
                + "\"payload\":{}}", true);
        assertThat(listener.awaitAck("alive-1", TIMEOUT)).isTrue();

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        assertThat(listener.closeCode.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(WebSocket.NORMAL_CLOSURE);
    }

    private SessionId createSession() {
        SessionView view = sessionApplicationService.createSession(
                TestFixtures.student(), taskId);
        sessionId = new SessionId(view.sessionId());
        return sessionId;
    }

    private WebSocket connect(SessionId id, HeartbeatListener listener) throws Exception {
        String ticket = issueTicket(id);
        return http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port
                        + "/ws/v1/sessions/" + id.value() + "?ticket=" + ticket), listener)
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private String issueTicket(SessionId id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/sessions/" + id.value()
                                + "/ws-ticket"))
                .header("X-Account-Id", TestFixtures.STUDENT_ID.toString())
                .header("X-School-Id", TestFixtures.SCHOOL_ID.toString())
                .header("X-Account-Type", "STUDENT")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("ws-ticket HTTP status: %s", response.body())
                .isEqualTo(200);
        return objectMapper.readTree(response.body()).get("ticket").asString();
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /** JDK WebSocket client that records JSON messages and optionally answers server pings. */
    private final class HeartbeatListener implements WebSocket.Listener {

        private final boolean respondToPing;
        private final AtomicInteger pingCount = new AtomicInteger();
        private final AtomicInteger pongSequence = new AtomicInteger();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final LinkedBlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        private HeartbeatListener(boolean respondToPing) {
            this.respondToPing = respondToPing;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                JsonNode message = objectMapper.readTree(partial.toString());
                partial.setLength(0);
                messages.add(message);
                if ("ping".equals(message.get("type").asString())) {
                    pingCount.incrementAndGet();
                    if (respondToPing) {
                        String commandId = "pong-" + pongSequence.incrementAndGet();
                        webSocket.sendText("{\"v\":1,\"type\":\"pong\",\"command_id\":\""
                                + commandId + "\",\"payload\":{\"client_time_ms\":0}}", true);
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.completeExceptionally(error);
        }

        private boolean awaitAck(String commandId, Duration timeout)
                throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode message = messages.poll(
                        Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L),
                        TimeUnit.MILLISECONDS);
                if (message == null) {
                    break;
                }
                if ("server.ack".equals(message.get("type").asString())
                        && commandId.equals(
                                message.get("payload").get("command_id").asString())) {
                    return true;
                }
            }
            return false;
        }
    }
}
