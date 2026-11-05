package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.IdGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Java-restart reconciliation for task sessions (05A §9.3).
 *
 * <p>An in-memory {@link SessionActor} is the only owner of a live classroom. When the process
 * restarts, any persisted {@code preparing}/{@code in_progress} task session can never be
 * resumed: it is marked {@code failed} with {@link FailureReason#INTERNAL} and a durable
 * {@code session.failed} event is appended at {@code maxSeq + 1}. Single-instance deployments
 * are the current P0 scope; there is no multi-instance ownership hand-off.
 */
@Component
public class SessionStartupReconciler implements ApplicationRunner {

    private static final Logger LOG =
            LoggerFactory.getLogger(SessionStartupReconciler.class);

    private static final List<SessionStatus> UNRECOVERABLE_STATUSES =
            List.of(SessionStatus.PREPARING, SessionStatus.IN_PROGRESS);
    private static final String SESSION_FAILED = "session.failed";

    private final SessionRepository sessionRepository;
    private final SessionEventStore eventStore;
    private final IdGenerator idGenerator;
    private final ClockProvider clock;

    public SessionStartupReconciler(
            SessionRepository sessionRepository,
            SessionEventStore eventStore,
            IdGenerator idGenerator,
            ClockProvider clock) {
        this.sessionRepository = Objects.requireNonNull(
                sessionRepository, "sessionRepository");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        reconcile();
    }

    /**
     * Marks every persisted task session that was left {@code preparing}/{@code in_progress} as
     * failed and appends one {@code session.failed} event per session.
     *
     * @return the number of reconciled sessions
     */
    public int reconcile() {
        Instant now = clock.now();
        List<Session> candidates = sessionRepository.findByStatusIn(UNRECOVERABLE_STATUSES)
                .stream()
                .filter(session -> session.type() == SessionType.TASK)
                .toList();
        int reconciled = 0;
        for (Session session : candidates) {
            session.fail(FailureReason.INTERNAL, now);
            sessionRepository.save(session);
            eventStore.append(new SessionEvent(
                    idGenerator.nextId(),
                    session.id(),
                    eventStore.maxSeq(session.id()) + 1L,
                    SESSION_FAILED,
                    atMs(session, now),
                    Map.of("reason", FailureReason.INTERNAL.wireValue()),
                    now));
            reconciled++;
            LOG.warn("Marked leftover task session {} as failed after restart "
                            + "(status before restart was preparing/in_progress)",
                    session.id().value());
        }
        LOG.info("Session startup reconciliation marked {} task session(s) failed", reconciled);
        return reconciled;
    }

    private static long atMs(Session session, Instant now) {
        if (session.startedAt() == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(session.startedAt(), now).toMillis());
    }
}
