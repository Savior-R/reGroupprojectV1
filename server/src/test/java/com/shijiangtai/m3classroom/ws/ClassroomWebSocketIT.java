package com.shijiangtai.m3classroom.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.application.SessionApplicationService;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.view.SessionView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig;
import com.shijiangtai.m3classroom.infrastructure.crossmodule.InMemoryCourseQueryAdapter;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end control channel test against a real Tomcat port (02 §6, 05A §8).
 *
 * <p>Uses the real task/session services and a real REST ticket request, then drives the raw
 * WebSocket with Spring's {@link StandardWebSocketClient}. The actor event sink is the single push
 * path, so every command event must arrive exactly once and must not be duplicated by the ack.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClassroomWebSocketIT extends PostgresIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int CLOSE_FAILED = 4002;

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
    private InMemoryCourseQueryAdapter courseQueryPort;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<WebSocketSession> openSockets = new ArrayList<>();

    private TaskId taskId;
    private SessionId sessionId;

    @BeforeEach
    void publishTask() {
        TaskView view = taskApplicationService.createDraft(
                TestFixtures.teacher(), TestFixtures.freeCommandWithStudent());
        taskId = new TaskId(view.taskId());
        materialService.upload(TestFixtures.teacher(), taskId, "a.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于课堂 WebSocket 测试。"
                        .getBytes(StandardCharsets.UTF_8));
        publishService.publish(TestFixtures.teacher(), taskId);
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

    @Test
    void ticketConnectSnapshotAndStart() throws Exception {
        SessionId id = createSession();
        Connection connection = connect(id);

        JsonNode snapshot = connection.nextOfType("session.snapshot");
        assertServerEnvelope(snapshot, id);
        assertThat(snapshot.get("payload").get("state").asString()).isEqualTo("preparing");
        assertThat(snapshot.get("payload").get("last_seq").asLong()).isZero();

        send(connection, "recording.ready", "ready-1", Map.of("streams", List.of(
                Map.of("stream_type", "camera", "status", "ready"),
                Map.of("stream_type", "screen", "status", "ready"),
                Map.of("stream_type", "audio", "status", "ready"))));

        JsonNode readyAck = connection.nextOfType("recording.ready_ack");
        assertServerEnvelope(readyAck, id);
        assertThat(readyAck.get("payload").get("ok").asBoolean()).isTrue();
        JsonNode readyServerAck = connection.nextOfType("server.ack");
        assertAck(readyServerAck, "ready-1", true);

        send(connection, "session.start", "start-1", Map.of());

        JsonNode started = connection.nextOfType("session.started");
        assertServerEnvelope(started, id);
        assertThat(started.get("seq").asLong()).isGreaterThan(readyAck.get("seq").asLong());
        JsonNode startAck = connection.nextOfType("server.ack");
        assertAck(startAck, "start-1", true);
        assertThat(connection.handler.countOfType("session.started")).isEqualTo(1);
    }

    @Test
    void duplicateCommandIdAcksOnce() throws Exception {
        SessionId id = createSession();
        Connection connection = connect(id);
        connection.nextOfType("session.snapshot");
        startSession(connection, "dup");

        send(connection, "session.start", "dup-start", Map.of());

        JsonNode duplicateAck = connection.nextOfType("server.ack");
        assertAck(duplicateAck, "dup-start", true);
        assertThat(connection.handler.countOfType("session.started")).isEqualTo(1);
    }

    @Test
    void invalidCommandDoesNotChangeState() throws Exception {
        SessionId id = createSession();
        Connection connection = connect(id);
        connection.nextOfType("session.snapshot");

        send(connection, "teacher.ask_class", "ask-invalid", Map.of());

        JsonNode error = connection.nextOfType("error");
        assertServerEnvelope(error, id);
        assertThat(error.get("payload").get("code").asString())
                .isEqualTo("SESSION_STATE_INVALID");
        assertThat(error.get("payload").get("command_id").asString()).isEqualTo("ask-invalid");
        assertThat(connection.handler.countOfType("classroom.question_asked")).isZero();
        assertThat(sessionApplicationService.get(TestFixtures.student(), id).status())
                .isEqualTo("preparing");
    }

    @Test
    void reconnectWithinGraceReplaysMissingEvents() throws Exception {
        SessionId id = createSession();
        Connection first = connect(id);
        first.nextOfType("session.snapshot");
        JsonNode started = startSession(first, "first");
        long seenThrough = started.get("seq").asLong();

        send(first, "teacher.ask_class", "ask-1", Map.of());
        JsonNode asked = first.nextOfType("classroom.question_asked");
        assertThat(asked.get("seq").asLong()).isGreaterThan(seenThrough);
        first.nextOfType("server.ack");
        long latestBeforeClose = first.handler.maxSeq();
        first.close();

        Connection second = connect(id);
        JsonNode snapshot = second.nextOfType("session.snapshot");
        assertThat(snapshot.get("payload").get("last_seq").asLong())
                .isGreaterThanOrEqualTo(asked.get("seq").asLong())
                .isGreaterThanOrEqualTo(latestBeforeClose);

        long snapshotLastSeq = snapshot.get("payload").get("last_seq").asLong();
        send(second, "session.resume", "resume-1", Map.of(
                "last_seq", seenThrough, "recording_state", "active"));

        List<JsonNode> untilAck = second.collectUntilAck("resume-1");
        int resumedIndex = indexOfType(untilAck, "session.resumed");
        assertThat(resumedIndex).as("session.resumed must be pushed on resume").isNotNegative();
        JsonNode resumed = untilAck.get(resumedIndex);
        assertThat(resumed.get("payload").get("last_seq").asLong()).isEqualTo(seenThrough);

        List<JsonNode> replay = untilAck.subList(resumedIndex + 1, untilAck.size()).stream()
                .filter(message -> !"server.ack".equals(type(message)))
                .toList();
        assertThat(replay).anyMatch(message ->
                "classroom.question_asked".equals(type(message))
                        && message.get("seq").asLong() > seenThrough
                        && message.get("seq").asLong() <= snapshotLastSeq);
        assertThat(replay).allSatisfy(message ->
                assertThat(message.get("seq").asLong()).isGreaterThan(seenThrough));
        assertThat(replay).noneMatch(message ->
                ("session.time_tick".equals(type(message)) || "ping".equals(type(message)))
                        && message.get("seq").asLong() <= snapshotLastSeq);
        JsonNode resumeAck = untilAck.get(untilAck.size() - 1);
        assertAck(resumeAck, "resume-1", true);
        // session.resumed carries the newest seq and is pushed before the lower-seq replay
        // tail; the ack must keep that maximum instead of rewinding to the last replayed event.
        assertThat(resumeAck.get("seq").asLong())
                .isGreaterThanOrEqualTo(resumed.get("seq").asLong());
    }

    @Test
    void resumeWithLostRecordingFailsSession() throws Exception {
        SessionId id = createSession();
        Connection connection = connect(id);
        connection.nextOfType("session.snapshot");
        startSession(connection, "start-lost");

        send(connection, "session.resume", "resume-lost",
                Map.of("last_seq", 0, "recording_state", "lost"));

        JsonNode failed = connection.nextOfType("session.failed");
        assertServerEnvelope(failed, id);
        assertThat(failed.get("payload").get("reason").asString())
                .isEqualTo("recording_lost");
        JsonNode error = connection.nextOfType("error");
        assertThat(error.get("payload").get("code").asString())
                .isEqualTo("SESSION_FAILED");
        assertThat(sessionApplicationService.get(TestFixtures.student(), id).status())
                .isEqualTo("failed");
    }

    @Test
    void normalEndSessionDoesNotFail() throws Exception {
        SessionId id = createSession();
        Connection connection = connect(id);
        connection.nextOfType("session.snapshot");
        startSession(connection, "start-ok");

        send(connection, "teacher.end_session", "end-ok", Map.of());

        connection.nextOfType("session.ended");
        connection.nextOfType("server.ack");
        assertThat(connection.handler.countOfType("session.failed")).isZero();
        assertThat(sessionApplicationService.get(TestFixtures.student(), id).status())
                .isEqualTo("ended");
    }

    @Test
    void reconnectOutsideGraceCloses4002() throws Exception {
        SessionId id = createSession();
        Connection first = connect(id);
        first.nextOfType("session.snapshot");
        first.close();

        // The production scheduler cannot be advanced in an integration test: simulate the
        // "outside grace" outcome by failing the session through the same actor path.
        String ticket = issueTicket(id);
        runtimeRegistry.find(id).orElseThrow().fail(FailureReason.GRACE_TIMEOUT);

        Connection second = connectWithTicket(id, ticket);
        CloseStatus closeStatus = second.handler.closed.get(
                TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(closeStatus.getCode()).isEqualTo(CLOSE_FAILED);
        assertThat(sessionApplicationService.get(TestFixtures.student(), id).status())
                .isEqualTo("failed");
        assertThat(runtimeRegistry.find(id)).isEmpty();
    }

    @Test
    void secondConnectionReplacesTheFirstWithNormalClose() throws Exception {
        SessionId id = createSession();
        Connection first = connect(id);
        first.nextOfType("session.snapshot");
        startSession(first, "replace");

        Connection second = connect(id);
        JsonNode snapshot = second.nextOfType("session.snapshot");
        assertThat(snapshot.get("payload").get("state").asString()).isEqualTo("in_progress");

        CloseStatus replacedClose = first.handler.closed.get(
                TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(replacedClose.getCode()).isEqualTo(CloseStatus.NORMAL.getCode());

        send(second, "teacher.ask_class", "ask-after-replace", Map.of());
        second.nextOfType("classroom.question_asked");
        assertAck(second.nextOfType("server.ack"), "ask-after-replace", true);
    }

    @Test
    void invalidTicketCloses4001() throws Exception {
        SessionId id = createSession();
        Connection connection = connectWithTicket(id, "not-a-ticket");

        CloseStatus closeStatus = connection.handler.closed.get(
                TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(closeStatus.getCode()).isEqualTo(4001);
    }

    @Test
    void timeSyncAckCarriesServerTime() throws Exception {
        SessionId id = createSession();
        Connection connection = connect(id);
        connection.nextOfType("session.snapshot");
        startSession(connection, "sync");

        long before = System.currentTimeMillis();
        send(connection, "time.sync", "time-sync-1", Map.of("client_time_ms", 123L));
        JsonNode ack = connection.nextOfType("server.ack");
        long after = System.currentTimeMillis();

        assertAck(ack, "time-sync-1", true);
        JsonNode payload = ack.get("payload");
        assertThat(payload.has("at_ms")).isTrue();
        assertThat(payload.get("at_ms").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(payload.get("server_time_ms").asLong())
                .isBetween(before - 1_000L, after + 1_000L);
    }

    @Test
    void oversizedFrameCloses4005() throws Exception {
        SessionId id = createSession();
        Connection connection = connect(id);
        connection.nextOfType("session.snapshot");

        String oversized = "{\"v\":1,\"type\":\"time.sync\",\"command_id\":\"big-1\","
                + "\"payload\":{\"client_time_ms\":0,\"padding\":\""
                + "a".repeat(70_000) + "\"}}";
        connection.socket.sendMessage(new TextMessage(oversized));

        CloseStatus closeStatus = connection.handler.closed.get(
                TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(closeStatus.getCode()).isEqualTo(4005);
    }

    @Test
    void terminalSessionConnectClosesNormallyAndEvictsActor() throws Exception {
        SessionId id = createSession();
        Connection first = connect(id);
        first.nextOfType("session.snapshot");
        startSession(first, "terminal");

        send(first, "teacher.end_session", "terminal-end", Map.of());
        first.nextOfType("session.ended");
        first.nextOfType("server.ack");
        first.close();
        assertThat(runtimeRegistry.find(id)).isEmpty();

        String ticket = issueTicket(id);
        Connection terminal = connectWithTicket(id, ticket);
        JsonNode snapshot = terminal.nextOfType("session.snapshot");
        assertThat(snapshot.get("payload").get("state").asString()).isEqualTo("ended");

        CloseStatus closeStatus = terminal.handler.closed.get(
                TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.NORMAL.getCode());
        assertThat(runtimeRegistry.find(id)).isEmpty();
    }

    @Test
    void interviewFlowShowsAdvancesAndEndsQuestions() throws Exception {
        TaskId interviewTaskId = publishInterviewTask();
        SessionView created = sessionApplicationService.createSession(
                TestFixtures.student(), interviewTaskId);
        SessionId id = new SessionId(created.sessionId());
        sessionId = id;

        Connection connection = connect(id);
        JsonNode snapshot = connection.nextOfType("session.snapshot");
        assertServerEnvelope(snapshot, id);
        assertThat(snapshot.get("payload").get("state").asString()).isEqualTo("preparing");
        assertThat(snapshot.get("payload").get("mode").asString()).isEqualTo("interview");

        send(connection, "recording.ready", "interview-ready", Map.of(
                "streams", List.of(
                        Map.of("stream_type", "camera", "status", "ready"),
                        Map.of("stream_type", "audio", "status", "ready"))));
        JsonNode readyAck = connection.nextOfType("recording.ready_ack");
        assertThat(readyAck.get("payload").get("required_streams").toString())
                .isEqualTo("[\"camera\",\"audio\"]");
        assertThat(readyAck.get("payload").get("ok").asBoolean()).isTrue();
        connection.nextOfType("server.ack");

        send(connection, "session.start", "interview-start", Map.of());
        JsonNode firstShown = connection.nextOfType("interview.question_shown");
        JsonNode firstAnswer = connection.nextOfType("interview.answer_started");
        connection.nextOfType("server.ack");
        assertThat(firstShown.get("payload").get("order").asInt()).isEqualTo(1);
        assertThat(firstShown.get("payload").get("total").asInt()).isEqualTo(2);
        assertThat(firstShown.get("payload").get("text").asString())
                .isEqualTo("请谈谈以学生为中心的理解。");
        assertThat(firstAnswer.get("seq").asLong())
                .isGreaterThan(firstShown.get("seq").asLong());

        send(connection, "interview.next_question", "interview-next", Map.of());
        JsonNode answerEnded = connection.nextOfType("interview.answer_ended");
        JsonNode nextQuestion = connection.nextOfType("interview.next_question");
        JsonNode secondShown = connection.nextOfType("interview.question_shown");
        JsonNode secondAnswer = connection.nextOfType("interview.answer_started");
        connection.nextOfType("server.ack");

        assertThat(List.of(
                answerEnded.get("seq").asLong(),
                nextQuestion.get("seq").asLong(),
                secondShown.get("seq").asLong(),
                secondAnswer.get("seq").asLong()))
                .isSorted()
                .doesNotHaveDuplicates();
        assertThat(nextQuestion.get("payload").get("order").asInt()).isEqualTo(2);
        assertThat(secondShown.get("payload").get("order").asInt()).isEqualTo(2);
        assertThat(secondShown.get("payload").get("text").asString())
                .isEqualTo("如何落实核心素养？");

        send(connection, "interview.end", "interview-end", Map.of());
        connection.nextOfType("interview.answer_ended");
        connection.nextOfType("interview.ended");
        JsonNode sessionEnded = connection.nextOfType("session.ended");
        connection.nextOfType("server.ack");
        assertThat(sessionEnded.get("payload").get("reason").asString()).isEqualTo("manual");
        assertThat(sessionApplicationService.get(TestFixtures.student(), id).status())
                .isEqualTo("ended");

        connection.close();
        assertThat(connection.handler.closed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .getCode()).isEqualTo(CloseStatus.NORMAL.getCode());
    }

    @Test
    void negativeResumeLastSeqReturnsValidationError() throws Exception {
        SessionId id = createSession();
        Connection connection = connect(id);
        connection.nextOfType("session.snapshot");

        send(connection, "session.resume", "resume-negative", Map.of(
                "last_seq", -1, "recording_state", "active"));

        JsonNode error = connection.nextOfType("error");
        assertServerEnvelope(error, id);
        assertThat(error.get("payload").get("code").asString())
                .isEqualTo("VALIDATION_ERROR");
        assertThat(error.get("payload").get("command_id").asString())
                .isEqualTo("resume-negative");
        assertThat(connection.socket.isOpen()).isTrue();
        assertThat(sessionApplicationService.get(TestFixtures.student(), id).status())
                .isEqualTo("preparing");
    }

    @Test
    void archivedCourseStartIsRejected() throws Exception {
        SessionId id = createSession();
        Connection connection = connect(id);
        connection.nextOfType("session.snapshot");
        sendReady(connection, "archived");

        try {
            courseQueryPort.addCourse(new CourseInfo(
                    TestFixtures.COURSE_ID, TestFixtures.SCHOOL_ID, "语文教学论", false,
                    Instant.parse("2027-06-30T00:00:00Z"), List.of(TestFixtures.TEACHER_ID)));

            send(connection, "session.start", "archived-start", Map.of());

            JsonNode error = connection.nextOfType("error");
            assertServerEnvelope(error, id);
            assertThat(error.get("payload").get("code").asString())
                    .isEqualTo("COURSE_ARCHIVED");
            assertThat(error.get("payload").get("command_id").asString())
                    .isEqualTo("archived-start");
            assertThat(connection.handler.countOfType("session.started")).isZero();
        } finally {
            courseQueryPort.addCourse(TestFixtures.course());
        }

        assertThat(sessionApplicationService.get(TestFixtures.student(), id).status())
                .isEqualTo("preparing");
    }

    @Test
    void endedCourseStartIsRejected() throws Exception {
        SessionId id = createSession();
        Connection connection = connect(id);
        connection.nextOfType("session.snapshot");
        sendReady(connection, "ended");

        try {
            courseQueryPort.addCourse(new CourseInfo(
                    TestFixtures.COURSE_ID, TestFixtures.SCHOOL_ID, "语文教学论", true,
                    clock.now().minusSeconds(1), List.of(TestFixtures.TEACHER_ID)));

            send(connection, "session.start", "ended-start", Map.of());

            JsonNode error = connection.nextOfType("error");
            assertServerEnvelope(error, id);
            assertThat(error.get("payload").get("code").asString())
                    .isEqualTo("COURSE_ENDED");
            assertThat(connection.handler.countOfType("session.started")).isZero();
        } finally {
            courseQueryPort.addCourse(TestFixtures.course());
        }

        assertThat(sessionApplicationService.get(TestFixtures.student(), id).status())
                .isEqualTo("preparing");
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private SessionId createSession() {
        SessionView view = sessionApplicationService.createSession(
                TestFixtures.student(), taskId);
        sessionId = new SessionId(view.sessionId());
        return sessionId;
    }

    private TaskId publishInterviewTask() {
        CreateTaskCommand command = new CreateTaskCommand(
                TestFixtures.COURSE, TaskMode.INTERVIEW, "结构化面试", null,
                300, 300, Map.of("content", 40, "expression", 30, "time", 30),
                null, List.of(),
                new QuestionConfig(List.of(
                        new QuestionId(DevDataConfig.QUESTION1_ID),
                        new QuestionId(DevDataConfig.QUESTION2_ID)), 300));
        TaskView view = taskApplicationService.createDraft(
                TestFixtures.teacher(), command);
        TaskId id = new TaskId(view.taskId());
        publishService.publish(TestFixtures.teacher(), id);
        return id;
    }

    private void sendReady(Connection connection, String commandPrefix) throws Exception {
        send(connection, "recording.ready", commandPrefix + "-ready", Map.of(
                "streams", List.of(
                        Map.of("stream_type", "camera", "status", "ready"),
                        Map.of("stream_type", "screen", "status", "ready"),
                        Map.of("stream_type", "audio", "status", "ready"))));
        connection.nextOfType("recording.ready_ack");
        connection.nextOfType("server.ack");
    }

    private JsonNode startSession(Connection connection, String commandPrefix) throws Exception {
        sendReady(connection, commandPrefix);

        send(connection, "session.start", commandPrefix + "-start", Map.of());
        JsonNode started = connection.nextOfType("session.started");
        connection.nextOfType("server.ack");
        return started;
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
                + "?ticket=" + ticket;
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

    private void send(Connection connection, String commandType, String commandId,
                      Map<String, Object> payload) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("v", 1);
        envelope.put("type", commandType);
        envelope.put("command_id", commandId);
        envelope.put("payload", payload);
        connection.socket.sendMessage(new TextMessage(
                objectMapper.writeValueAsString(envelope)));
    }

    private static void assertServerEnvelope(JsonNode message, SessionId id) {
        assertThat(message.propertyNames()).containsExactly(
                "v", "type", "event_id", "seq", "at_ms", "session_id", "payload");
        assertThat(message.get("v").asInt()).isEqualTo(1);
        assertThat(message.get("event_id").asString()).isNotBlank();
        assertThat(message.get("seq").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(message.get("at_ms").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(message.get("session_id").asString()).isEqualTo(id.value().toString());
        assertThat(message.get("payload").isObject()).isTrue();
    }

    private static void assertAck(JsonNode ack, String commandId, boolean ok) {
        assertThat(type(ack)).isEqualTo("server.ack");
        assertThat(ack.get("payload").get("command_id").asString()).isEqualTo(commandId);
        assertThat(ack.get("payload").get("ok").asBoolean()).isEqualTo(ok);
    }

    private static String type(JsonNode message) {
        return message.get("type").asString();
    }

    private static int indexOfType(List<JsonNode> messages, String messageType) {
        for (int index = 0; index < messages.size(); index++) {
            if (messageType.equals(type(messages.get(index)))) {
                return index;
            }
        }
        return -1;
    }

    private record Connection(WebSocketSession socket, TestHandler handler) {

        JsonNode nextOfType(String expectedType) throws InterruptedException {
            return handler.nextOfType(expectedType, TIMEOUT);
        }

        List<JsonNode> collectUntilAck(String commandId) throws InterruptedException {
            return handler.collectUntilAck(commandId, TIMEOUT);
        }

        void close() throws IOException {
            if (socket.isOpen()) {
                socket.close(CloseStatus.NORMAL);
            }
            try {
                handler.closed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                throw new IOException("connection did not close", exception);
            }
        }
    }

    private static final class TestHandler extends TextWebSocketHandler {

        private static final ObjectMapper MESSAGES =
                tools.jackson.databind.json.JsonMapper.builder().build();

        private final LinkedBlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final List<JsonNode> history = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.CompletableFuture<CloseStatus> closed =
                new java.util.concurrent.CompletableFuture<>();

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

        private List<JsonNode> collectUntilAck(String commandId, Duration timeout)
                throws InterruptedException {
            List<JsonNode> collected = new ArrayList<>();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode next = messages.poll(
                        Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L),
                        TimeUnit.MILLISECONDS);
                if (next == null) {
                    break;
                }
                collected.add(next);
                String type = type(next);
                if (("server.ack".equals(type) || "error".equals(type))
                        && commandId.equals(next.get("payload").get("command_id").asString())) {
                    return collected;
                }
            }
            throw new AssertionError("missing ack for " + commandId
                    + "; received " + types(collected));
        }

        private long countOfType(String messageType) {
            return history.stream().filter(node -> messageType.equals(type(node))).count();
        }

        private long maxSeq() {
            return history.stream().mapToLong(node -> node.get("seq").asLong()).max().orElse(0L);
        }

        private static List<String> types(List<JsonNode> nodes) {
            return nodes.stream().map(ClassroomWebSocketIT::type).toList();
        }
    }

}
