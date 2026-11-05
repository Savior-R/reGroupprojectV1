package com.shijiangtai.m3classroom.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionStartupReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final Instant RESTART_AT = NOW.plusSeconds(60);
    private static final TaskId TASK_ID =
            new TaskId(UUID.fromString("00000000-0000-0000-0000-000000000300"));
    private static final CourseId COURSE_ID =
            new CourseId(UUID.fromString("00000000-0000-0000-0000-000000000100"));
    private static final AccountId STUDENT =
            new AccountId(UUID.fromString("00000000-0000-0000-0000-000000000020"));

    @Test
    void marksLeftoverPreparingAndInProgressSessionsFailedWithEvent() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemorySessionEventStore events = new InMemorySessionEventStore();

        Session preparing = preparing(new SessionId(UUID.randomUUID()), 1);
        Session inProgress = preparing(new SessionId(UUID.randomUUID()), 2);
        inProgress.start(NOW);
        Session cancelled = preparing(new SessionId(UUID.randomUUID()), 3);
        cancelled.cancel(NOW);
        sessions.save(preparing);
        sessions.save(inProgress);
        sessions.save(cancelled);
        events.append(new SessionEvent(
                UUID.randomUUID(), inProgress.id(), 1L, "session.started", 0L,
                Map.of("started_at_ms", 0L), NOW));
        // Simulate events persisted by the previous process, including a high max seq.
        events.append(new SessionEvent(
                UUID.randomUUID(), preparing.id(), 7L, "session.time_tick", 0L,
                Map.of("elapsed_ms", 0L), NOW));

        SessionStartupReconciler reconciler = new SessionStartupReconciler(
                sessions, events, UUID::randomUUID, () -> RESTART_AT);

        int reconciled = reconciler.reconcile();

        assertThat(reconciled).isEqualTo(2);
        assertThat(sessions.findById(preparing.id()).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
        assertThat(sessions.findById(inProgress.id()).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
        assertThat(sessions.findById(cancelled.id()).orElseThrow().status())
                .isEqualTo(SessionStatus.CANCELLED);

        List<SessionEvent> preparingFailed = events.after(preparing.id(), 0L, 10).stream()
                .filter(event -> event.type().equals("session.failed"))
                .toList();
        assertThat(preparingFailed).hasSize(1);
        assertThat(preparingFailed.get(0).seq()).isEqualTo(8L);
        assertThat(preparingFailed.get(0).payload())
                .containsEntry("reason", "internal");

        List<SessionEvent> inProgressFailed = events.after(inProgress.id(), 0L, 10).stream()
                .filter(event -> event.type().equals("session.failed"))
                .toList();
        assertThat(inProgressFailed).hasSize(1);
        assertThat(inProgressFailed.get(0).seq()).isEqualTo(2L);
        assertThat(inProgressFailed.get(0).atMs()).isEqualTo(60_000L);
        assertThat(inProgressFailed.get(0).payload())
                .containsEntry("reason", "internal");
    }

    @Test
    void secondReconciliationIsNoOp() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemorySessionEventStore events = new InMemorySessionEventStore();
        Session session = preparing(new SessionId(UUID.randomUUID()), 1);
        sessions.save(session);
        SessionStartupReconciler reconciler = new SessionStartupReconciler(
                sessions, events, UUID::randomUUID, () -> RESTART_AT);

        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(reconciler.reconcile()).isZero();
        assertThat(events.all())
                .filteredOn(event -> event.type().equals("session.failed"))
                .hasSize(1);
    }

    private static Session preparing(SessionId sessionId, int attemptNo) {
        return Session.createPreparing(
                sessionId, SessionType.TASK, TASK_ID, COURSE_ID, STUDENT,
                TaskMode.FREE, attemptNo, NOW);
    }
}
