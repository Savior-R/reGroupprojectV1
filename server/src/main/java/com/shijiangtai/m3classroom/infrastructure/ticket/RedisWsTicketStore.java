package com.shijiangtai.m3classroom.infrastructure.ticket;

import com.shijiangtai.m3classroom.application.port.WsTicketClaims;
import com.shijiangtai.m3classroom.application.port.WsTicketStore;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis ticket store ({@code m3.ticket-store=redis}).
 *
 * <p>Values are stored as {@code {"session_id": "...", "account_id": "..."}} under
 * {@code ws_ticket:{ticket}} with a TTL ({@code SETEX} semantics); {@link #consume} uses
 * {@code GETDEL} so a ticket is redeemable exactly once (02 §2.3).
 */
@Component
@ConditionalOnProperty(name = "m3.ticket-store", havingValue = "redis")
public final class RedisWsTicketStore implements WsTicketStore {

    private static final String KEY_PREFIX = "ws_ticket:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisWsTicketStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String issue(WsTicketClaims claims, Duration ttl) {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        String ticket = UUID.randomUUID().toString();
        redis.opsForValue().set(KEY_PREFIX + ticket, write(claims), ttl);
        return ticket;
    }

    @Override
    public Optional<WsTicketClaims> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().getAndDelete(KEY_PREFIX + ticket);
        return json == null ? Optional.empty() : Optional.of(read(json));
    }

    private String write(WsTicketClaims claims) {
        try {
            return objectMapper.writeValueAsString(new TicketPayload(
                    claims.sessionId().value(), claims.accountId().value()));
        } catch (RuntimeException exception) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "WebSocket ticket 序列化失败",
                    Map.of("cause", exception.getClass().getSimpleName()));
        }
    }

    private WsTicketClaims read(String json) {
        try {
            TicketPayload payload = objectMapper.readValue(json, TicketPayload.class);
            return new WsTicketClaims(
                    new SessionId(payload.sessionId()), new AccountId(payload.accountId()));
        } catch (RuntimeException exception) {
            throw new DomainException(ErrorCode.WS_TICKET_INVALID, "WebSocket ticket 无效");
        }
    }

    /** Stored JSON shape; the injected mapper renders it as snake_case. */
    public record TicketPayload(UUID sessionId, UUID accountId) {
    }
}
