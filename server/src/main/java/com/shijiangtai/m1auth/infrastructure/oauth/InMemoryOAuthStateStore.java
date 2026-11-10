package com.shijiangtai.m1auth.infrastructure.oauth;

import com.shijiangtai.m1auth.application.port.OAuthStateStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 state / ticket 存储（03 §3.10「测试 profile 用内存实现」）。
 * 单实例有效，不跨进程；生产用 {@link RedisOAuthStateStore}。
 */
public class InMemoryOAuthStateStore implements OAuthStateStore {

    private record Entry<V>(V value, Instant expiresAt) {
        boolean alive(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    private final Map<String, Entry<ThreadState>> states = new ConcurrentHashMap<>();
    private final Map<String, Entry<TicketPayload>> loginTickets = new ConcurrentHashMap<>();
    private final Map<String, Entry<TicketPayload>> registrationTickets =
            new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryOAuthStateStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void create(String state, Duration ttl) {
        states.put(state, new Entry<>(new ThreadState(Status.PENDING, null), clock.instant().plus(ttl)));
    }

    @Override
    public Optional<ThreadState> status(String state) {
        Entry<ThreadState> entry = states.get(state);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.alive(clock.instant())) {
            states.remove(state);
            return Optional.of(new ThreadState(Status.EXPIRED, null));
        }
        return Optional.of(entry.value());
    }

    @Override
    public void markScanned(String state) {
        update(state, threadState -> new ThreadState(Status.SCANNED, threadState.ticket()));
    }

    @Override
    public void markAuthorized(String state, String ticket, TicketPayload payload, Duration ttl) {
        update(state, threadState -> new ThreadState(Status.AUTHORIZED, ticket));
        loginTickets.put(ticket, new Entry<>(payload, clock.instant().plus(ttl)));
    }

    @Override
    public void markNeedsRegistration(
            String state, String ticket, TicketPayload payload, Duration ttl) {
        update(state, threadState -> new ThreadState(Status.NEEDS_REGISTRATION, ticket));
        registrationTickets.put(ticket, new Entry<>(payload, clock.instant().plus(ttl)));
    }

    @Override
    public void markFailed(String state) {
        update(state, threadState -> new ThreadState(Status.FAILED, null));
    }

    @Override
    public Optional<TicketPayload> takeLoginTicket(String ticket) {
        Entry<TicketPayload> entry = loginTickets.remove(ticket);
        if (entry == null || !entry.alive(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public Optional<TicketPayload> findRegistrationTicket(String ticket) {
        Entry<TicketPayload> entry = registrationTickets.get(ticket);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.alive(clock.instant())) {
            registrationTickets.remove(ticket);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void deleteRegistrationTicket(String ticket) {
        registrationTickets.remove(ticket);
    }

    private void update(
            String state, java.util.function.UnaryOperator<ThreadState> change) {
        Entry<ThreadState> entry = states.get(state);
        if (entry == null || !entry.alive(clock.instant())) {
            return;
        }
        states.put(state, new Entry<>(change.apply(entry.value()), entry.expiresAt()));
    }
}
