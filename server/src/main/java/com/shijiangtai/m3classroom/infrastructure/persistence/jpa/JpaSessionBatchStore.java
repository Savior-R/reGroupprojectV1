package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.application.port.SessionBatchStore;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a drained event/text batch in one transaction.
 *
 * <p>Texts are written before events as a consumer convention: some event payloads carry a
 * {@code text_id} (for example {@code student.question} / {@code student.answer} / {@code
 * student.follow_up}) and a consumer may resolve it as soon as the event is visible, but there is
 * no database foreign key between events and texts. Event inserts are idempotent on
 * {@code (session_id, seq)}; texts upsert by id via {@code saveAll}. Either both writes commit or
 * neither does, so a requeued batch can never leave a half-persisted pair behind.
 *
 * <p>Each batch runs in its own {@code REQUIRES_NEW} transaction. A terminal flush executed from
 * inside a REST command transaction (for example {@code SessionApplicationServiceImpl.end}) must
 * not mark that caller transaction rollback-only when the batch write fails: the actor has
 * already transitioned and the registry still has to commit the derived {@code failed} row.
 * Suspending the caller also avoids contending for its uncommitted session-row UPDATE.
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(List<SessionEvent> events, List<SessionText> texts) {
        if (!texts.isEmpty()) {
            textStore.saveAll(texts);
        }
        if (!events.isEmpty()) {
            eventStore.appendAll(events);
        }
    }
}
