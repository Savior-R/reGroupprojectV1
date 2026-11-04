package com.shijiangtai.m3classroom.api;

import com.shijiangtai.m3classroom.application.WsTicketService;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.SessionId;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin REST adapter for one-time WebSocket tickets (02 §2.3). */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/ws-ticket")
public class WsTicketController {

    private final WsTicketService ticketService;
    private final CurrentUserProvider currentUserProvider;

    public WsTicketController(
            WsTicketService ticketService, CurrentUserProvider currentUserProvider) {
        this.ticketService = ticketService;
        this.currentUserProvider = currentUserProvider;
    }

    /** Serialized as {@code {ticket, expires_at}} (ISO-8601 Instant, snake_case globally). */
    @PostMapping
    public WsTicketService.IssuedTicket issue(@PathVariable UUID sessionId) {
        return ticketService.issue(
                currentUserProvider.require(), new SessionId(sessionId));
    }
}
