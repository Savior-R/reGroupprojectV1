package com.shijiangtai.m3classroom.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.SessionStartGuard;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.support.FakeContentClient;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.m3classroom.support.FakeScheduler;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.m3classroom.support.InMemorySessionTextStore;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler-level regression tests that need a repository failure, which is hard to arrange through
 * the real JPA stack used by {@link ClassroomWebSocketIT}.
 */
class ClassroomWebSocketHandlerTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final UUID COURSE_ID = TestFixtures.COURSE_ID;
    private static final UUID STUDENT_ID = TestFixtures.STUDENT_ID;
    private static final UUID TEACHER_ID = TestFixtures.TEACHER_ID;

    @Test
    void startPersistenceFailureSendsErrorAndCloses4002InsteadOfAck() throws Exception {
        ToggleSessionRepository sessions = new ToggleSessionRepository();
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        Task task = publishedTask();
        tasks.save(task);
        SessionId sessionId = new SessionId(UUID.randomUUID());
        sessions.save(Session.createPreparing(
                sessionId, SessionType.TASK, task.id(), new CourseId(COURSE_ID),
                new AccountId(STUDENT_ID), TaskMode.FREE, 1, NOW));

        FakeScheduler scheduler = new FakeScheduler();
        InMemorySessionEventStore events = new InMemorySessionEventStore();
        InMemorySessionTextStore texts = new InMemorySessionTextStore();
        SessionRuntimeRegistry registry = new SessionRuntimeRegistry(
                sessions, tasks, events, texts, UUID::randomUUID, () -> NOW, scheduler,
                new FakeContentClient());
        FakeCourseQueryPort courses = new FakeCourseQueryPort();
        courses.addCourse(TestFixtures.course());
        SessionStartGuard startGuard = new SessionStartGuard(
                sessions, tasks, courses, () -> NOW);
        ObjectMapper mapper = JsonMapper.builder().build();
        ClassroomWebSocketHandler handler = new ClassroomWebSocketHandler(
                registry, new WsConnectionRegistry(), sessions, startGuard,
                new WsCommandParser(mapper), () -> NOW, scheduler, mapper);

        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("ws-1");
        when(socket.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WsHandshakeInterceptor.ATTR_SESSION_ID, sessionId);
        attributes.put(WsHandshakeInterceptor.ATTR_ACCOUNT_ID, new AccountId(STUDENT_ID));
        when(socket.getAttributes()).thenReturn(attributes);
        handler.afterConnectionEstablished(socket);

        send(handler, socket, mapper, "recording.ready", "ready-1", Map.of(
                "streams", List.of(
                        Map.of("stream_type", "camera", "status", "ready"),
                        Map.of("stream_type", "screen", "status", "ready"),
                        Map.of("stream_type", "audio", "status", "ready"))));
        assertThat(messages(socket)).anyMatch(message ->
                "server.ack".equals(type(message))
                        && "ready-1".equals(commandId(message)));

        sessions.failSaves();
        send(handler, socket, mapper, "session.start", "start-1", Map.of());

        List<JsonNode> sent = messages(socket);
        assertThat(sent).anyMatch(message ->
                "error".equals(type(message))
                        && "start-1".equals(commandId(message))
                        && "INTERNAL_ERROR".equals(
                                message.get("payload").get("code").asString()));
        assertThat(sent).noneMatch(message ->
                "server.ack".equals(type(message))
                        && "start-1".equals(commandId(message))
                        && message.get("payload").get("ok").asBoolean());

        ArgumentCaptor<CloseStatus> closeCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(socket).close(closeCaptor.capture());
        assertThat(closeCaptor.getValue().getCode()).isEqualTo(4002);
        assertThat(registry.find(sessionId).orElseThrow().session().status())
                .isEqualTo(SessionStatus.IN_PROGRESS);
    }

    private static void send(
            ClassroomWebSocketHandler handler,
            WebSocketSession socket,
            ObjectMapper mapper,
            String type,
            String commandId,
            Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("v", 1);
        envelope.put("type", type);
        envelope.put("command_id", commandId);
        envelope.put("payload", payload);
        handler.handleTextMessage(socket, new TextMessage(mapper.writeValueAsString(envelope)));
    }

    private static List<JsonNode> messages(WebSocketSession socket) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, atLeastOnce()).sendMessage(captor.capture());
        List<JsonNode> parsed = new ArrayList<>();
        ObjectMapper mapper = JsonMapper.builder().build();
        for (TextMessage message : captor.getAllValues()) {
            parsed.add(mapper.readTree(message.getPayload()));
        }
        return parsed;
    }

    private static String type(JsonNode message) {
        return message.get("type").asString();
    }

    private static String commandId(JsonNode message) {
        JsonNode payload = message.get("payload");
        return payload == null || !payload.has("command_id")
                ? null
                : payload.get("command_id").asString();
    }

    private static Task publishedTask() {
        Task task = Task.createDraft(
                new TaskId(UUID.randomUUID()), new CourseId(COURSE_ID),
                new AccountId(TEACHER_ID), TestFixtures.freeDraft(), NOW);
        TaskSnapshot snapshot = new TaskSnapshotFactory().create(
                task, TestFixtures.course(), List.of(), NOW);
        task.publish(snapshot, NOW);
        return task;
    }

    /** Session repository whose writes can be made to fail after setup. */
    private static final class ToggleSessionRepository implements SessionRepository {

        private final InMemorySessionRepository delegate = new InMemorySessionRepository();
        private boolean failSaves;

        private void failSaves() {
            failSaves = true;
        }

        @Override
        public Session save(Session session) {
            if (failSaves) {
                throw new IllegalStateException("session row unavailable");
            }
            return delegate.save(session);
        }

        @Override
        public Optional<Session> findById(SessionId id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Session> findActiveByStudentAndTask(
                AccountId studentAccountId, TaskId taskId) {
            return delegate.findActiveByStudentAndTask(studentAccountId, taskId);
        }

        @Override
        public List<Session> findByStudentAndTask(
                AccountId studentAccountId, TaskId taskId) {
            return delegate.findByStudentAndTask(studentAccountId, taskId);
        }

        @Override
        public List<Session> findByStatusIn(Collection<SessionStatus> statuses) {
            return delegate.findByStatusIn(statuses);
        }

        @Override
        public long countByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
            return delegate.countByStudentAndTask(studentAccountId, taskId);
        }
    }
}
