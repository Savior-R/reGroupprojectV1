package com.shijiangtai.m3classroom.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.application.port.ContextPersistenceWriter;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the production asynchronous writer against Postgres: an explicit flush is immediately
 * durable, and the 1 s scheduler persists without any manual call.
 */
class ContextPersistenceWriterIT extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final Duration SCHEDULED_FLUSH_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ContextPersistenceWriter writer;

    @Autowired
    private SessionEventStore eventStore;

    @Autowired
    private SessionTextStore textStore;

    private SessionId currentSessionId;

    @AfterEach
    void closeWriterQueue() {
        // The writer bean is shared for the whole context; close the queue before @Sql truncates
        // the session rows so a leftover scheduler retry cannot hit a missing FK target.
        if (currentSessionId != null) {
            writer.close(currentSessionId);
            currentSessionId = null;
        }
    }

    @Test
    void manualFlushPersistsEventAndText() {
        SessionId sessionId = seedSession();
        SessionEvent event = event(sessionId, 1);
        SessionText text = text(sessionId);

        writer.appendEvent(event);
        writer.saveText(text);
        writer.flush(sessionId);

        assertThat(eventStore.after(sessionId, 0, 10))
                .extracting(SessionEvent::seq)
                .containsExactly(1L);
        assertThat(textStore.bySession(sessionId, "teacher")).containsExactly(text);
    }

    @Test
    void scheduledFlushPersistsWithoutManualCall() throws InterruptedException {
        SessionId sessionId = seedSession();
        writer.appendEvent(event(sessionId, 2));

        List<SessionEvent> persisted = awaitEvents(sessionId, 2, SCHEDULED_FLUSH_TIMEOUT);

        assertThat(persisted).extracting(SessionEvent::seq).containsExactly(2L);
    }

    private List<SessionEvent> awaitEvents(SessionId sessionId, long seq, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<SessionEvent> events = eventStore.after(sessionId, seq - 1, 10);
        while (events.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50L);
            events = eventStore.after(sessionId, seq - 1, 10);
        }
        return events;
    }

    private SessionId seedSession() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO m3_classroom.tasks
                    (id, course_id, mode, topic, duration_recommended_seconds,
                     duration_max_seconds, created_by)
                VALUES (?, ?, 'free', '持久化测试', 60, 120, ?)
                """, taskId.value(), TestFixtures.COURSE_ID, TestFixtures.TEACHER_ID);
        SessionId sessionId = new SessionId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO m3_classroom.sessions
                    (id, session_type, task_id, course_id, student_account_id, mode, status)
                VALUES (?, 'task', ?, ?, ?, 'free', 'in_progress')
                """, sessionId.value(), taskId.value(), TestFixtures.COURSE_ID,
                TestFixtures.STUDENT_ID);
        currentSessionId = sessionId;
        return sessionId;
    }

    private static SessionEvent event(SessionId sessionId, long seq) {
        return new SessionEvent(
                UUID.randomUUID(), sessionId, seq, "session.time_tick", seq * 100L,
                Map.of("elapsed_ms", seq * 100L), NOW);
    }

    private static SessionText text(SessionId sessionId) {
        return new SessionText(
                UUID.randomUUID(), sessionId, "teacher", null, "asr", "utterance",
                "同学们好，今天我们学习荷塘月色。", 0L, 800L, 0L,
                new BigDecimal("0.95"), "whisper-1", null, "final");
    }
}
