package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.WsTicketClaims;
import com.shijiangtai.m3classroom.application.port.WsTicketStore;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Issues one-time WebSocket tickets bound to a session and its owning student (02 §2.3).
 *
 * <p>The repository is optional only while the JPA adapter is pending (Task 9): this keeps the
 * application context bootable for the intermediate Task 8 commit. Once the adapter exists every
 * context resolves it and {@link #issue} always verifies ownership through it.
 */
@Service
public final class WsTicketService {

    public static final Duration TICKET_TTL = Duration.ofSeconds(30);

    private final WsTicketStore ticketStore;
    private final Optional<SessionRepository> sessionRepository;
    private final ClockProvider clock;

    public WsTicketService(
            WsTicketStore ticketStore,
            Optional<SessionRepository> sessionRepository,
            ClockProvider clock) {
        this.ticketStore = Objects.requireNonNull(ticketStore, "ticketStore");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public IssuedTicket issue(CurrentUser student, SessionId sessionId) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(sessionId, "sessionId");
        SessionRepository repository = sessionRepository.orElseThrow(() -> new DomainException(
                ErrorCode.SERVICE_UNAVAILABLE, "会话存储尚未就绪"));
        Session session = repository.findById(sessionId).orElseThrow(() -> new DomainException(
                ErrorCode.RESOURCE_NOT_FOUND, "会话不存在",
                Map.of("session_id", sessionId.value().toString())));
        if (!session.studentAccountId().equals(student.accountId())) {
            throw new DomainException(ErrorCode.WS_SESSION_FORBIDDEN, "无权连接该会话",
                    Map.of("session_id", sessionId.value().toString()));
        }
        requireTicketableStatus(session);

        Instant expiresAt = clock.now().plus(TICKET_TTL);
        String ticket = ticketStore.issue(
                new WsTicketClaims(sessionId, student.accountId()), TICKET_TTL);
        return new IssuedTicket(ticket, expiresAt);
    }

    public WsTicketClaims consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw invalidTicket();
        }
        return ticketStore.consume(ticket).orElseThrow(WsTicketService::invalidTicket);
    }

    /**
     * Failed sessions are unrecoverable ({@code SESSION_FAILED}); cancelled and submitted
     * sessions are terminal but not failures ({@code SESSION_STATE_INVALID}) (02 §2.3/§4.3).
     */
    private static void requireTicketableStatus(Session session) {
        Map<String, Object> details = Map.of(
                "session_id", session.id().value().toString(),
                "status", session.status().wireValue());
        if (session.status() == SessionStatus.FAILED) {
            throw new DomainException(ErrorCode.SESSION_FAILED, "会话已失败", details);
        }
        if (session.status() == SessionStatus.CANCELLED
                || session.status() == SessionStatus.SUBMITTED) {
            throw new DomainException(ErrorCode.SESSION_STATE_INVALID, "当前状态不允许连接", details);
        }
    }

    private static DomainException invalidTicket() {
        return new DomainException(ErrorCode.WS_TICKET_INVALID, "WebSocket ticket 无效");
    }

    /** Ticket value plus its absolute expiry, serialized as {@code {ticket, expires_at}}. */
    public record IssuedTicket(String ticket, Instant expiresAt) {
        public IssuedTicket {
            Objects.requireNonNull(ticket, "ticket");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
