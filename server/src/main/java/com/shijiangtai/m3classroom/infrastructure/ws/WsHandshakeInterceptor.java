package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.application.WsTicketService;
import com.shijiangtai.m3classroom.application.port.WsTicketClaims;
import com.shijiangtai.shared.domain.DomainException;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Authenticates the control-WebSocket handshake with a one-time ticket (02 §6.1).
 *
 * <p>The ticket is consumed before the upgrade, so a failed handshake never establishes a
 * connection. The validated {@link WsTicketClaims} are stored in the session attributes for the
 * handler. A missing/expired ticket is rejected with HTTP 401; a ticket for a different session
 * is rejected with HTTP 403. The protocol-level 4001 close is reserved for connections that
 * somehow reach the handler without these attributes (for example an internal handshake fault).
 */
@Component
public final class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_SESSION_ID = "m3.ws.session_id";
    public static final String ATTR_ACCOUNT_ID = "m3.ws.account_id";

    private static final String PATH_PREFIX = "/ws/v1/sessions/";

    private final WsTicketService ticketService;

    public WsHandshakeInterceptor(WsTicketService ticketService) {
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String sessionId = sessionIdFromPath(request.getURI().getPath());
        String ticket = ticketFromQuery(request);
        if (sessionId == null || ticket == null || ticket.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        WsTicketClaims claims;
        try {
            claims = ticketService.consume(ticket);
        } catch (DomainException exception) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (!claims.sessionId().value().toString().equals(sessionId)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_SESSION_ID, claims.sessionId());
        attributes.put(ATTR_ACCOUNT_ID, claims.accountId());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // no-op
    }

    private static String sessionIdFromPath(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX)) {
            return null;
        }
        String remainder = path.substring(PATH_PREFIX.length());
        int slash = remainder.indexOf('/');
        if (slash >= 0) {
            remainder = remainder.substring(0, slash);
        }
        return remainder.isBlank() ? null : remainder;
    }

    private static String ticketFromQuery(ServerHttpRequest request) {
        return UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("ticket");
    }
}
