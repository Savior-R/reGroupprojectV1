package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.shared.domain.SessionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Append-only in-memory event stream for one session (05A §9.1).
 *
 * <p>Seq allocation is owned by the actor/context, starts at 1 and never rewinds. The id and
 * creation time suppliers are injected so the context stays free of infrastructure types.
 */
public final class SessionContext {

    private final SessionId sessionId;
    private final Supplier<UUID> eventIds;
    private final Supplier<Instant> createdAt;
    private final List<SessionEvent> events = new ArrayList<>();
    private long lastSeq;

    public SessionContext(SessionId sessionId, Supplier<UUID> eventIds,
                          Supplier<Instant> createdAt) {
        this(sessionId, eventIds, createdAt, 0L);
    }

    /**
     * Creates a context whose next sequence number is {@code persistedLastSeq + 1}.
     *
     * <p>Used when a Java restart rebuilds an actor from the durable event store: the in-memory
     * context does not reload the historical events, but it must never rewind {@code seq} and
     * collide with rows already persisted.
     */
    public SessionContext(SessionId sessionId, Supplier<UUID> eventIds,
                          Supplier<Instant> createdAt, long persistedLastSeq) {
        if (persistedLastSeq < 0) {
            throw new IllegalArgumentException("persistedLastSeq must not be negative");
        }
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.eventIds = Objects.requireNonNull(eventIds, "eventIds");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lastSeq = persistedLastSeq;
    }

    public SessionId sessionId() {
        return sessionId;
    }

    /** Allocates the next sequence number; only {@link #append(SessionEventDraft, long)} uses it. */
    public long nextSeq() {
        return lastSeq + 1;
    }

    public SessionEvent append(SessionEventDraft draft, long atMs) {
        Objects.requireNonNull(draft, "draft");
        lastSeq++;
        SessionEvent event = new SessionEvent(
                Objects.requireNonNull(eventIds.get(), "eventId"),
                sessionId,
                lastSeq,
                draft.type(),
                atMs,
                draft.payload(),
                Objects.requireNonNull(createdAt.get(), "createdAt"));
        events.add(event);
        return event;
    }

    /** Events with {@code seq > after}, in append order. */
    public List<SessionEvent> after(long after) {
        List<SessionEvent> result = new ArrayList<>();
        for (SessionEvent event : events) {
            if (event.seq() > after) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    public long lastSeq() {
        return lastSeq;
    }
}
