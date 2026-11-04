package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.WsTicketClaims;
import com.shijiangtai.m3classroom.application.port.WsTicketStore;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Duration;
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

    public WsTicketService(
            WsTicketStore ticketStore, Optional<SessionRepository> sessionRepository) {
        this.ticketStore = Objects.requireNonNull(ticketStore, "ticketStore");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
    }

    public String issue(CurrentUser student, SessionId sessionId) {
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
        return ticketStore.issue(new WsTicketClaims(sessionId, student.accountId()), TICKET_TTL);
    }

    public WsTicketClaims consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw invalidTicket();
        }
        return ticketStore.consume(ticket).orElseThrow(WsTicketService::invalidTicket);
    }

    private static DomainException invalidTicket() {
        return new DomainException(ErrorCode.WS_TICKET_INVALID, "WebSocket ticket 无效");
    }
}
