package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable session-failure write for a lost recording when no live actor is available.
 *
 * <p>Always runs in its own new transaction: the recording mutation transaction may have rolled
 * back, and it must never overlap the session row lock held by that transaction (the caller
 * invokes this only after the mutation transaction completed). Before writing, the session is
 * re-read so an actor that already persisted {@code failed} — or a {@code session.failed} event
 * that already reached the durable log — does not produce a second event.
 */
@Service
class RecordingSessionFailureService {

    private static final Logger log =
            LoggerFactory.getLogger(RecordingSessionFailureService.class);
    /**
     * Upper bound for the duplicate-event check. Sessions emit at most one tick per second, so a
     * P0 session (bounded to a few hours) is far below this cap; the scan only runs on the rare
     * no-actor failure path.
     */
    private static final int FAILED_EVENT_SCAN_LIMIT = 100_000;

    private final SessionRepository sessionRepository;
    private final SessionEventStore eventStore;
    private final IdGenerator idGenerator;

    RecordingSessionFailureService(
            SessionRepository sessionRepository,
            SessionEventStore eventStore,
            IdGenerator idGenerator) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failDirectly(SessionId sessionId, Instant now) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(now, "now");
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || isTerminalForRecordingFailure(session.status())) {
            return;
        }
        session.fail(FailureReason.RECORDING_LOST, now);
        sessionRepository.save(session);
        if (!hasSessionFailedEvent(sessionId)) {
            appendSessionFailed(session, now);
        } else {
            log.debug("Session {} already has a durable session.failed event; not appending again",
                    sessionId.value());
        }
    }

    private boolean hasSessionFailedEvent(SessionId sessionId) {
        List<SessionEvent> events =
                eventStore.after(sessionId, 0, FAILED_EVENT_SCAN_LIMIT);
        return events.stream().anyMatch(event -> "session.failed".equals(event.type()));
    }

    private void appendSessionFailed(Session session, Instant now) {
        long seq = eventStore.maxSeq(session.id()) + 1;
        long atMs = session.durationMs() == null ? 0L : session.durationMs();
        eventStore.append(new SessionEvent(
                idGenerator.nextId(),
                session.id(),
                seq,
                "session.failed",
                atMs,
                Map.of("reason", FailureReason.RECORDING_LOST.wireValue()),
                now));
    }

    private static boolean isTerminalForRecordingFailure(SessionStatus status) {
        return status == SessionStatus.SUBMITTED
                || status == SessionStatus.CANCELLED
                || status == SessionStatus.FAILED;
    }
}