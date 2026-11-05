package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import java.util.List;

/**
 * Writes one drained batch of session events and texts in a single transaction.
 *
 * <p>The asynchronous {@link ContextPersistenceWriter} drains a per-session queue and hands the
 * batch here; implementations must persist the texts before the events so an event's
 * {@code text_id} reference is already durable (05A §9.3).
 */
public interface SessionBatchStore {

    void write(List<SessionEvent> events, List<SessionText> texts);
}
