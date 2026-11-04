package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.application.WsTicketService;
import com.shijiangtai.m3classroom.application.port.WsTicketClaims;
import com.shijiangtai.shared.domain.DomainException;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Validates a one-time ticket during the control-WebSocket handshake (02 §6.1).
 *
 * <p>The handshake is always allowed to complete so the client receives the protocol close codes
 * instead of an opaque HTTP 401/403 upgrade failure. Authentication failures are carried in
 * {@link #ATTR_AUTH_ERROR} and mapped by the handler:
 * <ul>
 *   <li>{@link #AUTH_INVALID_TICKET} / {@link #AUTH_FORBIDDEN} → close 4001;</li>
 *   <li>{@link #AUTH_FAILED} → close 4002;</li>
 *   <li>{@link #AUTH_STATE} → close 4003.</li>
 * </ul>
 * A valid ticket puts {@link #ATTR_SESSION_ID}/{@link #ATTR_ACCOUNT_ID} into the session
 * attributes; the ticket is consumed atomically, so a replay fails authentication.
 */
@Component
public final class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_SESSION_ID = "m3.ws.session_id";
    public static final String ATTR_ACCOUNT_ID = "m3.ws.account_id";
    public static final String ATTR_AUTH_ERROR = "m3.ws.auth_error";

    public static final String AUTH_INVALID_TICKET = "invalid_ticket";
    public static final String AUTH_FORBIDDEN = "forbidden";
    public static final String AUTH_FAILED = "failed";
    public static final String AUTH_STATE = "state";

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
            attributes.put(ATTR_AUTH_ERROR, AUTH_INVALID_TICKET);
            return true;
        }

        WsTicketClaims claims;
        try {
            claims = ticketService.consume(ticket);
        } catch (DomainException exception) {
            attributes.put(ATTR_AUTH_ERROR, AUTH_INVALID_TICKET);
            return true;
        }
        if (!claims.sessionId().value().toString().equals(sessionId)) {
            attributes.put(ATTR_AUTH_ERROR, AUTH_FORBIDDEN);
            return true;
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
