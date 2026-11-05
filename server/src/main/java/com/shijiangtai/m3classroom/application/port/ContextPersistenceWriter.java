package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.shared.domain.SessionId;

/**
 * Write port for one session's event log and unified text stream.
 *
 * <p>The direct implementation writes through synchronously; the asynchronous implementation
 * buffers per session and drains on a size/time threshold or an explicit {@link #flush}.
 */
public interface ContextPersistenceWriter {

    void appendEvent(SessionEvent event);

    void saveText(SessionText text);

    /** Drains all pending writes for the session; throws PersistenceFlushException on failure. */
    void flush(SessionId sessionId);

    default void close(SessionId sessionId) { }
}
