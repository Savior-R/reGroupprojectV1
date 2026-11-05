package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.port.PersistenceFlushException;
import com.shijiangtai.m3classroom.application.port.SessionBatchStore;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class JpaContextPersistenceWriterTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final RecordingBatchStore store = new RecordingBatchStore();
    private final JpaContextPersistenceWriter writer =
            new JpaContextPersistenceWriter(store, Runnable::run);

    @Test
    void flushWritesBufferedEventsAndTexts() {
        SessionId id = new SessionId(UUID.randomUUID());
        writer.appendEvent(event(id, 1));
        writer.saveText(text(id));
        assertThat(store.writes()).isEmpty();

        writer.flush(id);

        assertThat(store.writes()).hasSize(1);
        assertThat(store.writes().get(0).events()).extracting(SessionEvent::seq)
                .containsExactly(1L);
        assertThat(store.writes().get(0).texts()).hasSize(1);
    }

    @Test
    void flushFailureRetriesThenThrowsAndKeepsPending() {
        SessionId id = new SessionId(UUID.randomUUID());
        store.failNext(4);
        writer.appendEvent(event(id, 1));

        assertThatThrownBy(() -> writer.flush(id))
                .isInstanceOf(PersistenceFlushException.class);

        store.resetFailure();
        writer.flush(id);
        assertThat(store.allEvents()).extracting(SessionEvent::seq).containsExactly(1L);
    }

    @Test
    void hundredItemsTriggerAsyncFlush() {
        SessionId id = new SessionId(UUID.randomUUID());

        for (int i = 1; i <= 100; i++) {
            writer.appendEvent(event(id, i));
        }

        assertThat(store.writes()).isNotEmpty();
        assertThat(store.allEvents()).hasSize(100);
    }

    @Test
    void asyncFailureKeepsBatchForNextFlush() {
        SessionId id = new SessionId(UUID.randomUUID());
        store.failNext(4);

        for (int i = 1; i <= 100; i++) {
            writer.appendEvent(event(id, i));
        }

        // The inline executor ran the size-triggered flush and its four attempts failed, so the
        // whole batch was requeued and nothing reached the store.
        assertThat(store.allEvents()).isEmpty();

        store.resetFailure();
        writer.flush(id);

        assertThat(store.allEvents()).extracting(SessionEvent::seq)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 100).boxed().toList());
    }

    @Test
    void closeFlushesAndForgetsQueue() {
        SessionId id = new SessionId(UUID.randomUUID());
        writer.appendEvent(event(id, 1));

        writer.close(id);

        assertThat(store.allEvents()).hasSize(1);

        // The queue is forgotten: a later flush for the same id is a no-op.
        writer.flush(id);
        assertThat(store.writes()).hasSize(1);
    }

    private static SessionEvent event(SessionId id, long seq) {
        return new SessionEvent(
                UUID.randomUUID(), id, seq, "test.event", seq,
                Map.of("seq", seq), NOW);
    }

    private static SessionText text(SessionId id) {
        return new SessionText(
                UUID.randomUUID(), id, "teacher", null, "asr", "utterance",
                "同学们好", 0L, 100L, 0L, null, null, null, "final");
    }

    private record Write(List<SessionEvent> events, List<SessionText> texts) {
    }

    private static final class RecordingBatchStore implements SessionBatchStore {

        private final List<Write> writes = new CopyOnWriteArrayList<>();
        private final AtomicInteger failures = new AtomicInteger();

        @Override
        public void write(List<SessionEvent> events, List<SessionText> texts) {
            if (failures.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0) {
                throw new IllegalStateException("simulated write failure");
            }
            writes.add(new Write(List.copyOf(events), List.copyOf(texts)));
        }

        void failNext(int count) {
            failures.set(count);
        }

        void resetFailure() {
            failures.set(0);
        }

        List<Write> writes() {
            return List.copyOf(writes);
        }

        List<SessionEvent> allEvents() {
            return writes.stream().flatMap(write -> write.events().stream()).toList();
        }
    }
}
