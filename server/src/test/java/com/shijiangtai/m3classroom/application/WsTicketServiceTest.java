package com.shijiangtai.m3classroom.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.application.WsTicketService.IssuedTicket;
import com.shijiangtai.m3classroom.application.port.WsTicketClaims;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.infrastructure.ticket.InMemoryWsTicketStore;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WsTicketServiceTest {

    private static final SessionId SESSION_ID =
            new SessionId(UUID.fromString("00000000-0000-0000-0000-000000000600"));
    private static final TaskId TASK_ID =
            new TaskId(UUID.fromString("00000000-0000-0000-0000-000000000500"));

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final MutableClock clock = new MutableClock(TestFixtures.NOW);
    private WsTicketService service;

    @BeforeEach
    void setUp() {
        service = new WsTicketService(
                new InMemoryWsTicketStore(clock), Optional.of(sessions), clock);
    }

    @Test
    void issuesOneTimeTicketForOwner() {
        sessions.save(sessionOwnedBy(TestFixtures.STUDENT));

        IssuedTicket issued = service.issue(TestFixtures.student(), SESSION_ID);

        assertThat(issued.ticket()).isNotBlank();
        assertThat(issued.expiresAt()).isEqualTo(TestFixtures.NOW.plusSeconds(30));
        WsTicketClaims claims = service.consume(issued.ticket());
        assertThat(claims.sessionId()).isEqualTo(SESSION_ID);
        assertThat(claims.accountId()).isEqualTo(TestFixtures.STUDENT);

        assertThatThrownBy(() -> service.consume(issued.ticket()))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.WS_TICKET_INVALID));
    }

    @Test
    void ticketExpiresAfterThirtySeconds() {
        sessions.save(sessionOwnedBy(TestFixtures.STUDENT));
        String beforeBoundary = service.issue(TestFixtures.student(), SESSION_ID).ticket();
        clock.advance(Duration.ofSeconds(29));
        assertThat(service.consume(beforeBoundary)).isNotNull();

        IssuedTicket atBoundary = service.issue(TestFixtures.student(), SESSION_ID);
        assertThat(atBoundary.expiresAt()).isEqualTo(clock.now().plusSeconds(30));
        clock.advance(Duration.ofSeconds(30));

        assertThatThrownBy(() -> service.consume(atBoundary.ticket()))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.WS_TICKET_INVALID));
    }

    @Test
    void rejectsOtherStudentsSession() {
        sessions.save(sessionOwnedBy(TestFixtures.STUDENT));

        assertCode(() -> service.issue(TestFixtures.student2(), SESSION_ID),
                ErrorCode.WS_SESSION_FORBIDDEN);
    }

    @Test
    void rejectsMissingSession() {
        assertCode(() -> service.issue(TestFixtures.student(), SESSION_ID),
                ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void rejectsFailedSession() {
        Session session = sessionOwnedBy(TestFixtures.STUDENT);
        session.fail(FailureReason.RECORDING_LOST, TestFixtures.NOW);
        sessions.save(session);

        assertCode(() -> service.issue(TestFixtures.student(), SESSION_ID),
                ErrorCode.SESSION_FAILED);
    }

    @Test
    void rejectsCancelledSession() {
        Session session = sessionOwnedBy(TestFixtures.STUDENT);
        session.cancel(TestFixtures.NOW);
        sessions.save(session);

        assertCode(() -> service.issue(TestFixtures.student(), SESSION_ID),
                ErrorCode.SESSION_STATE_INVALID);
    }

    @Test
    void rejectsSubmittedSession() {
        Session session = sessionOwnedBy(TestFixtures.STUDENT);
        session.start(TestFixtures.NOW);
        session.end(TestFixtures.NOW.plusSeconds(1));
        session.submit(TestFixtures.NOW.plusSeconds(2));
        sessions.save(session);

        assertCode(() -> service.issue(TestFixtures.student(), SESSION_ID),
                ErrorCode.SESSION_STATE_INVALID);
    }

    @Test
    void rejectsMissingOrBlankTicket() {
        assertCode(() -> service.consume(null), ErrorCode.WS_TICKET_INVALID);
        assertCode(() -> service.consume("  "), ErrorCode.WS_TICKET_INVALID);
    }

    private static void assertCode(ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expected));
    }

    private static Session sessionOwnedBy(AccountId owner) {
        return Session.createPreparing(
                SESSION_ID, SessionType.TASK, TASK_ID, TestFixtures.COURSE, owner,
                TaskMode.FREE, 1, TestFixtures.NOW);
    }

    private static final class MutableClock implements ClockProvider {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
