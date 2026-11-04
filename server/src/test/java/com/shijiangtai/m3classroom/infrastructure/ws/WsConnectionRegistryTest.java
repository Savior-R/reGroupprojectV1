package com.shijiangtai.m3classroom.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class WsConnectionRegistryTest {

    private static final SessionId SESSION_ID = new SessionId(UUID.randomUUID());

    @Test
    void registeringReplacesThePreviousConnectionAndSendsOnlyToIt() {
        WsConnectionRegistry registry = new WsConnectionRegistry();
        List<SessionEvent> first = new ArrayList<>();
        List<SessionEvent> second = new ArrayList<>();

        Consumer<SessionEvent> replaced = registry.register(SESSION_ID, first::add);
        assertThat(replaced).isNull();
        assertThat(registry.register(SESSION_ID, second::add)).isNotNull();

        SessionEvent event = event(1);
        registry.send(SESSION_ID, event);

        assertThat(first).isEmpty();
        assertThat(second).containsExactly(event);
        assertThat(registry.isConnected(SESSION_ID)).isTrue();
    }

    @Test
    void removeOnlyUnregistersTheMatchingConnection() {
        WsConnectionRegistry registry = new WsConnectionRegistry();
        Consumer<SessionEvent> first = event -> { };
        Consumer<SessionEvent> second = event -> { };
        registry.register(SESSION_ID, first);
        registry.register(SESSION_ID, second);

        assertThat(registry.remove(SESSION_ID, first)).isFalse();
        assertThat(registry.isConnected(SESSION_ID)).isTrue();
        assertThat(registry.remove(SESSION_ID, second)).isTrue();
        assertThat(registry.isConnected(SESSION_ID)).isFalse();
    }

    @Test
    void sinkAdapterDropsEventsWhileDisconnectedAndForwardsWhenConnected() {
        WsConnectionRegistry registry = new WsConnectionRegistry();
        SessionEventSinkAdapter adapter = new SessionEventSinkAdapter(registry, SESSION_ID);
        SessionEvent event = event(1);

        adapter.accept(event);

        List<SessionEvent> received = new ArrayList<>();
        registry.register(SESSION_ID, received::add);
        adapter.accept(event);

        assertThat(received).containsExactly(event);
    }

    @Test
    void throwingSinkIsUnregisteredAndFailureCallbackRuns() {
        List<SessionId> failures = new ArrayList<>();
        WsConnectionRegistry registry = new WsConnectionRegistry(failures::add);
        registry.register(SESSION_ID, event -> {
            throw new IllegalStateException("socket closed");
        });

        registry.send(SESSION_ID, event(1));

        assertThat(failures).containsExactly(SESSION_ID);
        assertThat(registry.isConnected(SESSION_ID)).isFalse();
    }

    @Test
    void throwingFailureCallbackDoesNotEscape() {
        WsConnectionRegistry registry = new WsConnectionRegistry(sessionId -> {
            throw new IllegalStateException("grace handler failed");
        });
        registry.register(SESSION_ID, event -> {
            throw new IllegalStateException("socket closed");
        });

        registry.send(SESSION_ID, event(1));

        assertThat(registry.isConnected(SESSION_ID)).isFalse();
    }

    private static SessionEvent event(long seq) {
        return new SessionEvent(
                UUID.randomUUID(), SESSION_ID, seq, "session.time_tick", 0L,
                Map.of("elapsed_ms", 0L), Instant.parse("2026-11-04T00:00:00Z"));
    }
}
