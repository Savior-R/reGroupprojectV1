package com.shijiangtai.m1auth.infrastructure.oauth;

import com.shijiangtai.m1auth.application.port.OAuthStateStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 版 state / ticket 存储（03 §3.10「存储：OAuth state / ticket 使用 Redis」）。
 * 值用简单分隔编码，避免额外序列化依赖；ticket 为 UUID，不含分隔符。
 */
public class RedisOAuthStateStore implements OAuthStateStore {

    private static final String STATE_KEY = "m1:oauth:state:";
    private static final String LOGIN_TICKET_KEY = "m1:oauth:login-ticket:";
    private static final String REG_TICKET_KEY = "m1:oauth:reg-ticket:";
    private static final String SEP = "|";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisOAuthStateStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void create(String state, Duration ttl) {
        redis.opsForValue().set(STATE_KEY + state, Status.PENDING.name(), ttl);
    }

    @Override
    public Optional<ThreadState> status(String state) {
        String value = redis.opsForValue().get(STATE_KEY + state);
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\\" + SEP, 2);
        Status status = Status.valueOf(parts[0]);
        String ticket = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
        return Optional.of(new ThreadState(status, ticket));
    }

    @Override
    public void markScanned(String state) {
        String key = STATE_KEY + state;
        Duration ttl = remainingTtl(key);
        if (ttl != null) {
            redis.opsForValue().set(key, Status.SCANNED.name(), ttl);
        }
    }

    @Override
    public void markAuthorized(String state, String ticket, TicketPayload payload, Duration ttl) {
        String key = STATE_KEY + state;
        Duration remaining = remainingTtl(key);
        if (remaining != null) {
            redis.opsForValue().set(key, Status.AUTHORIZED.name() + SEP + ticket, remaining);
        }
        redis.opsForValue().set(LOGIN_TICKET_KEY + ticket, encode(payload), ttl);
    }

    @Override
    public void markNeedsRegistration(
            String state, String ticket, TicketPayload payload, Duration ttl) {
        String key = STATE_KEY + state;
        Duration remaining = remainingTtl(key);
        if (remaining != null) {
            redis.opsForValue().set(
                    key, Status.NEEDS_REGISTRATION.name() + SEP + ticket, remaining);
        }
        redis.opsForValue().set(REG_TICKET_KEY + ticket, encode(payload), ttl);
    }

    @Override
    public void markFailed(String state) {
        String key = STATE_KEY + state;
        Duration ttl = remainingTtl(key);
        if (ttl != null) {
            redis.opsForValue().set(key, Status.FAILED.name(), ttl);
        }
    }

    @Override
    public Optional<TicketPayload> takeLoginTicket(String ticket) {
        String key = LOGIN_TICKET_KEY + ticket;
        String value = redis.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        redis.delete(key);
        return Optional.of(decode(value));
    }

    @Override
    public Optional<TicketPayload> findRegistrationTicket(String ticket) {
        String value = redis.opsForValue().get(REG_TICKET_KEY + ticket);
        return value == null ? Optional.empty() : Optional.of(decode(value));
    }

    @Override
    public void deleteRegistrationTicket(String ticket) {
        redis.delete(REG_TICKET_KEY + ticket);
    }

    private Duration remainingTtl(String key) {
        Long seconds = redis.getExpire(key);
        return seconds == null || seconds <= 0 ? null : Duration.ofSeconds(seconds);
    }

    private static String encode(TicketPayload payload) {
        return String.join(SEP,
                nullSafe(payload.provider()),
                nullSafe(payload.providerUserId()),
                nullSafe(payload.unionId()),
                nullSafe(payload.email()),
                nullSafe(payload.nickname()),
                nullSafe(payload.avatarUrl()));
    }

    private static TicketPayload decode(String value) {
        List<String> parts = List.of(value.split("\\" + SEP, -1));
        return new TicketPayload(
                part(parts, 0), part(parts, 1), part(parts, 2),
                part(parts, 3), part(parts, 4), part(parts, 5));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.replace(SEP, " ");
    }

    private static String part(List<String> parts, int index) {
        if (index >= parts.size()) {
            return null;
        }
        String value = parts.get(index);
        return value.isBlank() ? null : value;
    }
}
