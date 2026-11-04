package com.shijiangtai.m3classroom.infrastructure.ticket;

import com.shijiangtai.m3classroom.application.port.WsTicketClaims;
import com.shijiangtai.m3classroom.application.port.WsTicketStore;
import com.shijiangtai.shared.application.ClockProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Test/default ticket store ({@code m3.ticket-store=memory}): UUID tickets with a TTL enforced
 * against the injected clock.
 */
@Component
@ConditionalOnProperty(name = "m3.ticket-store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryWsTicketStore implements WsTicketStore {

    private final ClockProvider clock;
    private final Map<String, StoredTicket> tickets = new ConcurrentHashMap<>();

    public InMemoryWsTicketStore(ClockProvider clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String issue(WsTicketClaims claims, Duration ttl) {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, new StoredTicket(claims, clock.now().plus(ttl)));
        return ticket;
    }

    @Override
    public Optional<WsTicketClaims> consume(String ticket) {
        if (ticket == null) {
            return Optional.empty();
        }
        StoredTicket stored = tickets.remove(ticket);
        if (stored == null || !clock.now().isBefore(stored.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(stored.claims());
    }

    private record StoredTicket(WsTicketClaims claims, Instant expiresAt) {
    }
}
