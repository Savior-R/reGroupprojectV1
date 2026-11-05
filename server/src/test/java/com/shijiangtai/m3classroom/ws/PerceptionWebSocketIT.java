package com.shijiangtai.m3classroom.ws;

import static com.shijiangtai.m3classroom.TestFixtures.SCHOOL_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT_ID;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.application.SessionApplicationService;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.port.AsrChunk;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.application.view.SessionView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.m3classroom.support.FakeAsrStreamClient;
import com.shijiangtai.m3classroom.support.FakeTeacherSpeechClassifier;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end test of the perception WebSocket (05A §6.2) against a real Tomcat port.
 *
 * <p>Uses the real task/session services and a real REST ticket request; the ASR client and
 * teacher-speech classifier are replaced by controllable fakes so the test never depends on the
 * Python service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PerceptionWebSocketIT.PerceptionTestConfig.class)
class PerceptionWebSocketIT extends PostgresIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int CLOSE_NORMAL = 1000;
    private static final int CLOSE_AUTH = 4001;
    private static final int CLOSE_FAILED = 4002;
    private static final int CLOSE_STATE = 4003;

    private static final String AUDIO_START =
            "{\"type\":\"audio.start\",\"format\":\"pcm16\","
                    + "\"sample_rate\":16000,\"channels\":1}";
    private static final String AUDIO_STOP = "{\"type\":\"audio.stop\"}";

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
    private SessionRepository sessionRepository;

    @Autowired
    private SessionTextStore textStore;

    @Autowired
    private SessionEventStore eventStore;

    @Autowired
    private ClockProvider clock;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FakeAsrStreamClient asrClient;

    @Autowired
    private FakeTeacherSpeechClassifier classifier;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<WebSocketSession> openSockets = new ArrayList<>();

    private TaskId taskId;
    private SessionId sessionId;

    @TestConfiguration
    static class PerceptionTestConfig {

        @Bean
        @Primary
        FakeAsrStreamClient fakeAsrStreamClient() {
            return new FakeAsrStreamClient();
        }

        @Bean
        @Primary
        FakeTeacherSpeechClassifier fakeTeacherSpeechClassifier() {
            return new FakeTeacherSpeechClassifier();
        }
    }

    @BeforeEach
    void setUp() {
        asrClient.reset();
        classifier.reset();
        TaskView view = taskApplicationService.createDraft(
                teacher(), TestFixtures.freeCommandWithStudent());
        taskId = new TaskId(view.taskId());
        materialService.upload(teacher(), taskId, "a.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于感知 WebSocket 测试。"
                        .getBytes(StandardCharsets.UTF_8));
        publishService.publish(teacher(), taskId);
    }

    @AfterEach
    void cleanUp() {
        for (WebSocketSession socket : openSockets) {
            try {
                if (socket.isOpen()) {
                    socket.close(CloseStatus.NORMAL);
                }
            } catch (IOException ignored) {
                // best-effort test cleanup
            }
        }
        openSockets.clear();
        if (sessionId != null) {
            runtimeRegistry.find(sessionId).ifPresent(actor -> {
                try {
                    if (actor.isInProgress()) {
                        actor.cancel(clock.now());
                    }
                } catch (RuntimeException ignored) {
                    // the row is truncated by @Sql before the next test anyway
                }
                runtimeRegistry.remove(sessionId);
            });
            sessionId = null;
        }
    }

    // ------------------------------------------------------------------
    // ASR streaming and persistence
    // ------------------------------------------------------------------

    @Test
    void partialAndFinalArePushedAndFinalIsPersisted() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);
        send(connection, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession asr = awaitAsrSession();

        asr.emitPartial("大家看这里", 0L, 900L, new BigDecimal("0.82"));

        JsonNode partial = connection.nextOfType("asr.partial");
        assertThat(partial.get("text").asString()).isEqualTo("大家看这里");
        assertThat(partial.get("at_ms").asLong()).isGreaterThanOrEqualTo(900L);

        asr.emitFinal("这是最后一句", 0L, 1500L, new BigDecimal("0.90"));

        JsonNode finalMessage = connection.nextOfType("asr.final");
        assertThat(finalMessage.get("text").asString()).isEqualTo("这是最后一句");
        assertThat(finalMessage.get("text_id").asString()).isNotBlank();
        assertThat(finalMessage.get("at_ms").asLong()).isGreaterThanOrEqualTo(1500L);

        SessionText text = awaitText(id, "teacher");
        assertThat(text.text()).isEqualTo("这是最后一句");
        assertThat(text.kind()).isEqualTo("utterance");
        assertThat(text.speakerType()).isEqualTo("teacher");
        assertThat(text.source()).isEqualTo("asr");
        assertThat(text.status()).isEqualTo("final");
    }

    @Test
    void binaryFramesAreForwardedToAsr() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);
        send(connection, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession asr = awaitAsrSession();

        byte[][] frames = {{1, 2, 3}, {4, 5}, {6}};
        for (byte[] frame : frames) {
            connection.socket.sendMessage(new BinaryMessage(frame));
        }

        awaitSentFrames(asr, frames.length);
        List<byte[]> sent = asr.sentFrames();
        assertThat(sent).hasSize(frames.length);
        for (int index = 0; index < frames.length; index++) {
            assertThat(Arrays.equals(sent.get(index), frames[index]))
                    .as("PCM frame %s forwarded unchanged", index)
                    .isTrue();
        }
    }

    @Test
    void highConfidenceQuestionTriggersVoiceQa() throws Exception {
        classifier.respondWith("question", "0.90");
        SessionId id = createStartedSession();
        Connection connection = connect(id);
        send(connection, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession asr = awaitAsrSession();

        asr.emitFinal("同学们，这篇课文的中心思想是什么？", 0L, 1000L,
                new BigDecimal("0.92"));

        SessionEvent asked = awaitEvent(id, "classroom.question_asked");
        assertThat(String.valueOf(asked.payload().get("source"))).isEqualTo("voice");
        SessionEvent stateChanged =
                awaitEventAfter(id, "classroom.state_changed", asked.seq());
        assertThat(stateChanged.seq()).isGreaterThan(asked.seq());
        assertThat(classifier.calls()).anySatisfy(call -> {
            assertThat(call.text()).contains("中心思想");
            assertThat(call.mode()).isEqualTo("free");
            assertThat(call.recentTexts()).isEmpty();
        });
    }

    @Test
    void lowConfidenceQuestionOnlySuggests() throws Exception {
        classifier.respondWith("question", "0.40");
        SessionId id = createStartedSession();
        Connection connection = connect(id);
        send(connection, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession asr = awaitAsrSession();

        asr.emitFinal("这句话可能是问题吗？", 0L, 1000L, new BigDecimal("0.70"));

        awaitEvent(id, "classroom.question_suggested");
        assertThat(eventStore.after(id, 0, 200))
                .noneMatch(event -> "classroom.question_asked".equals(event.type()));
    }

    @Test
    void asrOpenFailureKeepsSessionAlive() throws Exception {
        asrClient.failOnOpen(new RuntimeException("ASR 服务不可用"));
        SessionId id = createStartedSession();
        Connection connection = connect(id);

        send(connection, AUDIO_START);

        JsonNode error = connection.nextOfType("error");
        assertThat(error.get("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");

        send(connection, AUDIO_STOP);

        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_NORMAL);
        assertThat(sessionRepository.findById(id).orElseThrow().status())
                .isEqualTo(SessionStatus.IN_PROGRESS);
    }

    @Test
    void audioStopAloneCloses1000AfterDrainTimeout() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);
        send(connection, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession asr = awaitAsrSession();

        long startedAt = System.nanoTime();
        send(connection, AUDIO_STOP);
        CloseStatus closeStatus = connection.awaitClose();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        // No ASR "done" is emitted: only the 2 s drain timeout can close the socket.
        assertThat(asr.finished()).isTrue();
        assertThat(closeStatus.getCode()).isEqualTo(CLOSE_NORMAL);
        assertThat(elapsedMs).isBetween(1_800L, 9_000L);
        assertThat(sessionRepository.findById(id).orElseThrow().status())
                .isEqualTo(SessionStatus.IN_PROGRESS);

        // A late ASR callback after the drain close is ignored without persisting or throwing.
        asr.emitFinal("迟到的最终结果", 0L, 500L, new BigDecimal("0.80"));
        assertThat(textStore.bySession(id, "teacher")).isEmpty();
        assertThat(asr.callbackErrors()).isEmpty();
    }

    @Test
    void midStreamAsrErrorKeepsSocketOpenThenStopCloses1000() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);
        send(connection, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession asr = awaitAsrSession();

        asr.emitError(new RuntimeException("ASR 连接中断"));

        JsonNode error = connection.nextOfType("error");
        assertThat(error.get("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(connection.socket.isOpen()).isTrue();
        assertThat(sessionRepository.findById(id).orElseThrow().status())
                .isEqualTo(SessionStatus.IN_PROGRESS);

        send(connection, AUDIO_STOP);

        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_NORMAL);
        assertThat(sessionRepository.findById(id).orElseThrow().status())
                .isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(eventStore.after(id, 0, 200))
                .noneMatch(event -> "session.failed".equals(event.type()));
    }

    @Test
    void unexpectedAsrCloseKeepsSocketOpenUntilStop() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);
        send(connection, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession asr = awaitAsrSession();

        asr.emitClosed();

        JsonNode error = connection.nextOfType("error");
        assertThat(error.get("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(connection.socket.isOpen()).isTrue();

        send(connection, AUDIO_STOP);

        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_NORMAL);
        assertThat(sessionRepository.findById(id).orElseThrow().status())
                .isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(eventStore.after(id, 0, 200))
                .noneMatch(event -> "session.failed".equals(event.type()));
    }

    @Test
    void interviewFinalIsPersistedAsCandidateAnswer() throws Exception {
        SessionId id = createStartedInterviewSession();
        UUID answerId = runtimeRegistry.find(id).orElseThrow().currentAnswerId();
        assertThat(answerId).isNotNull();

        Connection connection = connect(id);
        send(connection, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession asr = awaitAsrSession();

        asr.emitFinal("我的回答是……", 0L, 1200L, new BigDecimal("0.91"));

        JsonNode finalMessage = connection.nextOfType("asr.final");
        SessionText text = awaitText(id, "candidate");
        assertThat(text.speakerType()).isEqualTo("candidate");
        assertThat(text.kind()).isEqualTo("answer");
        assertThat(text.speakerRef()).isEqualTo(answerId);
        assertThat(text.text()).isEqualTo("我的回答是……");
        assertThat(finalMessage.get("text_id").asString()).isEqualTo(text.id().toString());

        // Classroom-only classification must not run for an interview candidate.
        Thread.sleep(200L);
        assertThat(classifier.calls()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Protocol validation
    // ------------------------------------------------------------------

    @Test
    void invalidStartCloses4003() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);

        send(connection, "{\"type\":\"audio.start\",\"format\":\"mp3\","
                + "\"sample_rate\":16000,\"channels\":1}");

        JsonNode error = connection.nextOfType("error");
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_STATE);
    }

    @Test
    void binaryBeforeStartCloses4003() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);

        connection.socket.sendMessage(new BinaryMessage(new byte[] {1, 2, 3}));

        JsonNode error = connection.nextOfType("error");
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_STATE);
    }

    @Test
    void duplicateStartCloses4003() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);
        send(connection, AUDIO_START);
        awaitAsrSession();

        send(connection, AUDIO_START);

        JsonNode error = connection.nextOfType("error");
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_STATE);
    }

    @Test
    void audioStopBeforeStartCloses4003() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);

        send(connection, AUDIO_STOP);

        JsonNode error = connection.nextOfType("error");
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_STATE);
    }

    // ------------------------------------------------------------------
    // Handshake and lifecycle
    // ------------------------------------------------------------------

    @Test
    void invalidTicketCloses4001() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connectWithTicket(id, "not-a-ticket");

        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_AUTH);
    }

    @Test
    void preparingSessionCloses4003() throws Exception {
        SessionId id = createSession();
        Connection connection = connect(id);

        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_STATE);
    }

    @Test
    void terminalSessionCloses4002() throws Exception {
        SessionId id = createStartedSession();
        sessionApplicationService.end(student(), id);

        Connection connection = connect(id);

        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_FAILED);
    }

    @Test
    void secondConnectionReplacesFirst() throws Exception {
        SessionId id = createStartedSession();
        Connection first = connect(id);
        send(first, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession firstAsr = awaitAsrSession();

        Connection second = connect(id);

        assertThat(first.awaitClose().getCode()).isEqualTo(CLOSE_NORMAL);

        send(second, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession secondAsr = awaitAsrSession(2);

        assertThat(secondAsr).isNotSameAs(firstAsr);
        secondAsr.emitPartial("第二路", 0L, 200L, new BigDecimal("0.70"));
        assertThat(second.nextOfType("asr.partial").get("text").asString())
                .isEqualTo("第二路");
    }

    @Test
    void sessionEndClosesPerception4002() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);
        send(connection, AUDIO_START);
        awaitAsrSession();

        sessionApplicationService.end(student(), id);

        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_FAILED);
    }

    @Test
    void audioStopDrainsFinalThenCloses1000() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);
        send(connection, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession asr = awaitAsrSession();

        asr.emitFinalAfterFinish(
                new AsrChunk("这是最后一句", true, 0L, 1200L, new BigDecimal("0.92")),
                Duration.ofMillis(100));
        send(connection, AUDIO_STOP);

        JsonNode finalMessage = connection.nextOfType("asr.final");
        assertThat(finalMessage.get("text").asString()).isEqualTo("这是最后一句");
        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_NORMAL);
    }

    @Test
    void lateFinalAfterEndIsNotPersisted() throws Exception {
        SessionId id = createStartedSession();
        Connection connection = connect(id);
        send(connection, AUDIO_START);
        FakeAsrStreamClient.FakeAsrSession asr = awaitAsrSession();

        sessionApplicationService.end(student(), id);
        asr.emitFinal("结束后的话", 0L, 500L, new BigDecimal("0.90"));

        assertThat(connection.awaitClose().getCode()).isEqualTo(CLOSE_FAILED);
        assertThat(textStore.bySession(id, "teacher")).isEmpty();
        assertThat(asr.callbackErrors()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private SessionId createSession() {
        SessionView view = sessionApplicationService.createSession(student(), taskId);
        sessionId = new SessionId(view.sessionId());
        return sessionId;
    }

    private SessionId createStartedSession() {
        SessionId id = createSession();
        startSession(id, StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO);
        return id;
    }

    private SessionId createStartedInterviewSession() {
        TaskId interviewTaskId = publishInterviewTask();
        SessionView view = sessionApplicationService.createSession(
                student(), interviewTaskId);
        sessionId = new SessionId(view.sessionId());
        startSession(sessionId, StreamType.CAMERA, StreamType.AUDIO);
        return sessionId;
    }

    private void startSession(SessionId id, StreamType... requiredStreams) {
        SessionActor actor = runtimeRegistry.getOrCreate(id);
        List<SessionCommand.RecordingReady.StreamStatus> streams =
                Arrays.stream(requiredStreams)
                        .map(type -> new SessionCommand.RecordingReady.StreamStatus(
                                type, "ready"))
                        .toList();
        assertThat(actor.handle(new SessionCommand.RecordingReady(
                "ready-" + UUID.randomUUID(), streams)).ok()).isTrue();
        sessionApplicationService.start(student(), id);
    }

    private TaskId publishInterviewTask() {
        CreateTaskCommand command = new CreateTaskCommand(
                TestFixtures.COURSE, TaskMode.INTERVIEW, "结构化面试", null,
                300, 300, Map.of("content", 40, "expression", 30, "time", 30),
                null, List.of(),
                new QuestionConfig(List.of(
                        new QuestionId(DevDataConfig.QUESTION1_ID),
                        new QuestionId(DevDataConfig.QUESTION2_ID)), 300));
        TaskView view = taskApplicationService.createDraft(teacher(), command);
        TaskId id = new TaskId(view.taskId());
        publishService.publish(teacher(), id);
        return id;
    }

    private Connection connect(SessionId id) throws Exception {
        return connectWithTicket(id, issueTicket(id));
    }

    private Connection connectWithTicket(SessionId id, String ticket) throws Exception {
        TestHandler handler = new TestHandler();
        WebSocketSession socket = new StandardWebSocketClient()
                .execute(handler, wsUri(id, ticket))
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        openSockets.add(socket);
        return new Connection(socket, handler);
    }

    private String wsUri(SessionId id, String ticket) {
        return "ws://localhost:" + port + "/ws/v1/sessions/" + id.value()
                + "/perception?ticket=" + ticket;
    }

    private String issueTicket(SessionId id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/sessions/" + id.value()
                                + "/ws-ticket"))
                .header("X-Account-Id", STUDENT_ID.toString())
                .header("X-School-Id", SCHOOL_ID.toString())
                .header("X-Account-Type", "STUDENT")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("ws-ticket HTTP status: %s", response.body())
                .isEqualTo(200);
        return objectMapper.readTree(response.body()).get("ticket").asString();
    }

    private void send(Connection connection, String json) throws IOException {
        connection.socket.sendMessage(new TextMessage(json));
    }

    private FakeAsrStreamClient.FakeAsrSession awaitAsrSession() throws InterruptedException {
        return awaitAsrSession(1);
    }

    private FakeAsrStreamClient.FakeAsrSession awaitAsrSession(int minimumOpenCount)
            throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            FakeAsrStreamClient.FakeAsrSession session = asrClient.lastSession();
            if (session != null && asrClient.openCount() >= minimumOpenCount) {
                return session;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError(
                "ASR session was not opened; openCount=" + asrClient.openCount());
    }

    private void awaitSentFrames(FakeAsrStreamClient.FakeAsrSession asr, int expectedCount)
            throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (asr.sentFrames().size() >= expectedCount) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("only " + asr.sentFrames().size()
                + " PCM frames were forwarded to ASR");
    }

    private SessionText awaitText(SessionId id, String speakerType)
            throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            List<SessionText> texts = textStore.bySession(id, speakerType);
            if (!texts.isEmpty()) {
                return texts.get(0);
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("no " + speakerType + " session_texts row persisted for "
                + id.value());
    }

    private SessionEvent awaitEvent(SessionId id, String type) throws InterruptedException {
        return awaitEventAfter(id, type, 0L);
    }

    private SessionEvent awaitEventAfter(SessionId id, String type, long afterSeq)
            throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<SessionEvent> event = eventStore.after(id, afterSeq, 200).stream()
                    .filter(candidate -> type.equals(candidate.type()))
                    .findFirst();
            if (event.isPresent()) {
                return event.get();
            }
            Thread.sleep(25L);
        }
        throw new AssertionError(
                "event " + type + " not found after seq " + afterSeq + " for " + id.value());
    }

    private record Connection(WebSocketSession socket, TestHandler handler) {

        JsonNode nextOfType(String expectedType) throws InterruptedException {
            return handler.nextOfType(expectedType, TIMEOUT);
        }

        CloseStatus awaitClose() throws Exception {
            return handler.closed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static final class TestHandler extends AbstractWebSocketHandler {

        private static final ObjectMapper MESSAGES = JsonMapper.builder().build();

        private final LinkedBlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final List<JsonNode> history = new CopyOnWriteArrayList<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            JsonNode node = MESSAGES.readTree(message.getPayload());
            history.add(node);
            messages.add(node);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        private JsonNode nextOfType(String expectedType, Duration timeout)
                throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode next = messages.poll(
                        Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L),
                        TimeUnit.MILLISECONDS);
                if (next == null) {
                    break;
                }
                if (expectedType.equals(type(next))) {
                    return next;
                }
            }
            throw new AssertionError("missing message type " + expectedType
                    + "; received " + types(history));
        }

        private static List<String> types(List<JsonNode> nodes) {
            return nodes.stream().map(TestHandler::type).toList();
        }

        private static String type(JsonNode message) {
            return message.get("type").asString();
        }
    }
}
