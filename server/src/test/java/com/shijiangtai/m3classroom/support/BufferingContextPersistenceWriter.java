package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.ContextPersistenceWriter;
import com.shijiangtai.m3classroom.application.port.PersistenceFlushException;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.shared.domain.SessionId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Test writer that buffers events and texts until {@link #flush(SessionId)} drains them into the
 * backing in-memory stores. This mirrors the production asynchronous queue without a background
 * thread, so tests control exactly when writes become durable.
 */
public final class BufferingContextPersistenceWriter implements ContextPersistenceWriter {

    private final SessionEventStore events;
    private final SessionTextStore texts;
    private final List<SessionEvent> pendingEvents = new ArrayList<>();
    private final List<SessionText> pendingTexts = new ArrayList<>();

    /** Test control: when true, the next flush throws before draining the buffers. */
    public boolean failFlush;

    private int flushCalls;

    public BufferingContextPersistenceWriter(
            SessionEventStore events, SessionTextStore texts) {
        this.events = Objects.requireNonNull(events, "events");
        this.texts = Objects.requireNonNull(texts, "texts");
    }

    @Override
    public void appendEvent(SessionEvent event) {
        pendingEvents.add(Objects.requireNonNull(event, "event"));
    }

    @Override
    public void saveText(SessionText text) {
        pendingTexts.add(Objects.requireNonNull(text, "text"));
    }

    @Override
    public synchronized void flush(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        flushCalls++;
        if (failFlush) {
            throw new PersistenceFlushException(sessionId, "flush failed");
        }
        pendingTexts.forEach(texts::save);
        pendingEvents.forEach(events::append);
        pendingTexts.clear();
        pendingEvents.clear();
    }

    @Override
    public void close(SessionId sessionId) {
        flush(sessionId);
    }

    public synchronized int flushCalls() {
        return flushCalls;
    }

    public synchronized List<SessionEvent> pendingEvents() {
        return List.copyOf(pendingEvents);
    }

    public synchronized List<SessionText> pendingTexts() {
        return List.copyOf(pendingTexts);
    }
}
