package com.shijiangtai.m3classroom.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig;
import com.shijiangtai.m3classroom.infrastructure.persistence.jpa.SessionJsonCodec;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionEventPersistenceIT extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Autowired
    private SessionEventStore eventStore;

    @Autowired
    private SessionTextStore textStore;

    @Autowired
    private SessionJsonCodec jsonCodec;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void appendIsIdempotentOnSessionAndSeq() {
        SessionId sessionId = seedSession();
        UUID firstEventId = UUID.randomUUID();
        eventStore.append(new SessionEvent(
                firstEventId, sessionId, 1, "session.started", 0,
                Map.of("started_at_ms", 0), NOW));
        eventStore.append(new SessionEvent(
                UUID.randomUUID(), sessionId, 2, "classroom.state_changed", 1200,
                Map.of("from", "lecturing", "to", "qa"), NOW.plusSeconds(1)));

        eventStore.append(new SessionEvent(
                UUID.randomUUID(), sessionId, 1, "duplicate.event", 99,
                Map.of("ignored", true), NOW.plusSeconds(2)));

        assertThat(eventStore.maxSeq(sessionId)).isEqualTo(2);
        assertThat(countEvents(sessionId)).isEqualTo(2);
        List<SessionEvent> events = eventStore.after(sessionId, 0, 10);
        assertThat(events).extracting(SessionEvent::seq).containsExactly(1L, 2L);
        assertThat(events.get(0).eventId()).isEqualTo(firstEventId);
        assertThat(events.get(0).type()).isEqualTo("session.started");
        assertThat(events.get(0).atMs()).isZero();
        assertThat(events.get(0).payload()).containsEntry("started_at_ms", 0);
        assertThat(events.get(0).createdAt()).isEqualTo(NOW);
        assertThat(events.get(1).type()).isEqualTo("classroom.state_changed");
        assertThat(events.get(1).atMs()).isEqualTo(1200L);
        assertThat(events.get(1).payload())
                .containsEntry("from", "lecturing")
                .containsEntry("to", "qa");
        assertThat(events.get(1).createdAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void afterHonoursAfterSeqAndLimit() {
        SessionId sessionId = seedSession();
        for (long seq = 1; seq <= 3; seq++) {
            eventStore.append(new SessionEvent(
                    UUID.randomUUID(), sessionId, seq, "event." + seq, seq * 100,
                    Map.of("seq", seq), NOW));
        }

        assertThat(eventStore.after(sessionId, 1, 1))
                .extracting(SessionEvent::seq)
                .containsExactly(2L);
        assertThat(eventStore.after(sessionId, 0, 2))
                .extracting(SessionEvent::seq)
                .containsExactly(1L, 2L);
        assertThat(eventStore.after(sessionId, 3, 10)).isEmpty();
        assertThat(eventStore.after(sessionId, 0, 0)).isEmpty();
    }

    @Test
    void maxSeqIsZeroForUnknownSession() {
        assertThat(eventStore.maxSeq(new SessionId(UUID.randomUUID()))).isZero();
    }

    @Test
    void payloadIsStoredAsJsonb() {
        SessionId sessionId = seedSession();
        UUID studentId = UUID.randomUUID();
        eventStore.append(new SessionEvent(
                UUID.randomUUID(), sessionId, 1, "student.hand_raised", 1500,
                Map.of("student_id", studentId.toString(), "count", 3), NOW));

        String payloadJson = jdbc.queryForObject(
                "select payload::text from m3_classroom.events"
                        + " where session_id = ? and seq = 1",
                String.class, sessionId.value());

        assertThat(payloadJson).contains("\"student_id\"").contains("\"count\"");
        assertThat(jsonCodec.readEventPayload(payloadJson))
                .containsEntry("student_id", studentId.toString())
                .containsEntry("count", 3);
    }

    @Test
    void payloadRoundTripExposesJsonNativeValues() {
        SessionId sessionId = seedSession();
        UUID studentId = UUID.randomUUID();
        eventStore.append(new SessionEvent(
                UUID.randomUUID(), sessionId, 1, "student.hand_raised", 1500,
                Map.of("student_id", studentId, "seq_value", 3L), NOW));

        SessionEvent reloaded = eventStore.after(sessionId, 0, 10).get(0);

        assertThat(reloaded.seq()).isEqualTo(1L);
        assertThat(eventStore.maxSeq(sessionId)).isEqualTo(1L);
        assertThat(reloaded.payload().get("student_id"))
                .isInstanceOf(String.class)
                .isEqualTo(studentId.toString());
        assertThat(reloaded.payload().get("seq_value"))
                .isInstanceOf(Number.class)
                .extracting(value -> ((Number) value).longValue())
                .isEqualTo(3L);
    }

    @Test
    void savesAndReadsSessionTexts() {
        SessionId sessionId = seedSession();
        SessionText teacherText = new SessionText(
                UUID.randomUUID(), sessionId, "teacher", null, "asr", "utterance",
                "同学们好，今天我们学习荷塘月色。", 0L, 800L, 0L,
                new BigDecimal("0.95"), "whisper-1", null, "final");
        SessionText studentText = new SessionText(
                UUID.randomUUID(), sessionId, "student", UUID.randomUUID(), "llm", "answer",
                "老师，我认为这一段的重点是……", null, null, 2500L,
                new BigDecimal("0.80"), "qwen", "v1", "final");

        textStore.save(teacherText);
        textStore.save(studentText);

        assertThat(textStore.bySession(sessionId, null))
                .containsExactly(teacherText, studentText);
        assertThat(textStore.bySession(sessionId, "teacher"))
                .containsExactly(teacherText);
        assertThat(textStore.bySession(sessionId, "student"))
                .containsExactly(studentText);
        assertThat(textStore.bySession(sessionId, "examiner")).isEmpty();
    }

    private SessionId seedSession() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO m3_classroom.tasks
                    (id, course_id, mode, topic, duration_recommended_seconds,
                     duration_max_seconds, created_by, status)
                VALUES (?, ?, 'free', '荷塘月色', 900, 1200, ?, 'published')
                """, taskId.value(), DevDataConfig.COURSE_ID, DevDataConfig.TEACHER_ID);
        SessionId sessionId = new SessionId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO m3_classroom.sessions
                    (id, session_type, task_id, course_id, student_account_id, mode, status)
                VALUES (?, 'task', ?, ?, ?, 'free', 'preparing')
                """, sessionId.value(), taskId.value(), DevDataConfig.COURSE_ID,
                DevDataConfig.STUDENT_ID);
        return sessionId;
    }

    private int countEvents(SessionId sessionId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from m3_classroom.events where session_id = ?",
                Integer.class, sessionId.value());
        return count == null ? 0 : count;
    }
}
