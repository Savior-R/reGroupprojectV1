package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.ContextPersistenceWriter;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.shared.domain.SessionId;
import java.util.Objects;

/**
 * Synchronous writer preserving the P0 persistence semantics: every event and text is written
 * through immediately, so there is never anything pending to flush.
 */
public final class DirectContextPersistenceWriter implements ContextPersistenceWriter {

    private final SessionEventStore eventStore;
    private final SessionTextStore textStore;

    public DirectContextPersistenceWriter(
            SessionEventStore eventStore, SessionTextStore textStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.textStore = Objects.requireNonNull(textStore, "textStore");
    }

    @Override
    public void appendEvent(SessionEvent event) {
        eventStore.append(Objects.requireNonNull(event, "event"));
    }

    @Override
    public void saveText(SessionText text) {
        textStore.save(Objects.requireNonNull(text, "text"));
    }

    @Override
    public void flush(SessionId sessionId) {
        // Nothing is buffered by the direct writer.
    }

    @Override
    public void close(SessionId sessionId) {
        // Nothing is buffered by the direct writer.
    }
}
