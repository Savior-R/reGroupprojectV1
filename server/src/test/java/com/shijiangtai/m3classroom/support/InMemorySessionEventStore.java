package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.shared.domain.SessionId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** In-memory append-only event log used by unit tests. */
public final class InMemorySessionEventStore implements SessionEventStore {

    private final List<SessionEvent> events = new ArrayList<>();

    @Override
    public synchronized void append(SessionEvent event) {
        events.add(event);
    }

    @Override
    public synchronized List<SessionEvent> after(SessionId id, long seq, int limit) {
        return events.stream()
                .filter(event -> event.sessionId().equals(id))
                .filter(event -> event.seq() > seq)
                .sorted(Comparator.comparingLong(SessionEvent::seq))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized long maxSeq(SessionId id) {
        return events.stream()
                .filter(event -> event.sessionId().equals(id))
                .mapToLong(SessionEvent::seq)
                .max()
                .orElse(0L);
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized List<SessionEvent> all() {
        return List.copyOf(events);
    }
}
