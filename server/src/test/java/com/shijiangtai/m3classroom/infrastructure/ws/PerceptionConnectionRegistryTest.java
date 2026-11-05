package com.shijiangtai.m3classroom.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class PerceptionConnectionRegistryTest {

    private static final SessionId SESSION_ID = new SessionId(UUID.randomUUID());
    private static final long TIMEOUT_MS = Duration.ofSeconds(5).toMillis();

    private PerceptionConnectionRegistry registry;
    private SessionActor actor;
    private AtomicReference<Consumer<SessionStatus>> statusListener;

    @BeforeEach
    void setUp() {
        registry = new PerceptionConnectionRegistry();
        actor = mock(SessionActor.class);
        statusListener = new AtomicReference<>();
        when(actor.addStatusListener(any())).thenAnswer(invocation -> {
            Consumer<SessionStatus> listener = invocation.getArgument(0);
            statusListener.set(listener);
            return (Runnable) () -> { };
        });
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    @Test
    void terminalStatusSchedulesBlockingCloseOffTheListenerThread() throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        AtomicReference<String> closeThread = new AtomicReference<>();
        doAnswer(invocation -> {
            closeStatus.set(invocation.getArgument(0));
            closeThread.set(Thread.currentThread().getName());
            order.add("close");
            closeEntered.countDown();
            releaseClose.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return null;
        }).when(socket).close(any(CloseStatus.class));

        registry.register(SESSION_ID, socket, actor, () -> order.add("freeze"));

        long startedAt = System.nanoTime();
        statusListener.get().accept(SessionStatus.ENDED);
        long listenerMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        // The actor thread returns immediately even though socket.close() is still blocked.
        assertThat(listenerMillis).isLessThan(500L);
        assertThat(closeEntered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(order).containsExactly("freeze", "close");
        assertThat(closeStatus.get().getCode())
                .isEqualTo(PerceptionConnectionRegistry.CLOSE_TERMINAL);
        assertThat(closeThread.get()).isEqualTo("perception-terminal-close");

        releaseClose.countDown();
    }

    @Test
    void registeringSecondConnectionFreezesPreviousBeforeClosingIt() throws Exception {
        WebSocketSession first = mock(WebSocketSession.class);
        when(first.isOpen()).thenReturn(true);
        WebSocketSession second = mock(WebSocketSession.class);
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicReference<CloseStatus> firstCloseStatus = new AtomicReference<>();
        doAnswer(invocation -> {
            firstCloseStatus.set(invocation.getArgument(0));
            order.add("close");
            return null;
        }).when(first).close(any(CloseStatus.class));

        registry.register(SESSION_ID, first, actor, () -> order.add("freeze"));
        registry.register(SESSION_ID, second, actor, () -> order.add("second-freeze"));

        // Freeze ran synchronously before the old socket was closed.
        assertThat(order).containsExactly("freeze", "close");
        assertThat(firstCloseStatus.get().getCode())
                .isEqualTo(PerceptionConnectionRegistry.CLOSE_REPLACED);
    }
}
