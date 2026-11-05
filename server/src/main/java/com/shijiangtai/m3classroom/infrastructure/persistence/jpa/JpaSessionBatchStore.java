package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.application.port.SessionBatchStore;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a drained event/text batch in one transaction.
 *
 * <p>Texts are written before events: an event may reference a text through {@code text_id}
 * (05A §9.3). Event inserts are idempotent on {@code (session_id, seq)}; texts upsert by id via
 * {@code saveAll}. Either both writes commit or neither does, so a requeued batch can never leave
 * a half-persisted pair behind.
 */
@Repository
public class JpaSessionBatchStore implements SessionBatchStore {

    private final SessionEventStore eventStore;
    private final SessionTextStore textStore;

    public JpaSessionBatchStore(SessionEventStore eventStore, SessionTextStore textStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.textStore = Objects.requireNonNull(textStore, "textStore");
    }

    @Override
    @Transactional
    public void write(List<SessionEvent> events, List<SessionText> texts) {
        if (!texts.isEmpty()) {
            textStore.saveAll(texts);
        }
        if (!events.isEmpty()) {
            eventStore.appendAll(events);
        }
    }
}
