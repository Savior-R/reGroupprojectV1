package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.shared.domain.SessionId;
import java.util.List;

/**
 * Durable append-only event log (05A §6.2, §9.2).
 *
 * <p>Payload contract: producers may put rich Java values ({@link java.util.UUID}, {@link Long},
 * ...) into a {@link SessionEvent}. Events written through a JSON-backed adapter (the production
 * JPA adapter) are stored as JSON, so events returned by {@link #after} contain only JSON-native
 * values: UUIDs are {@link String}s equal to {@code uuid.toString()}, and integral numbers are
 * {@link Number}s that may narrow (for example {@code Long} to {@code Integer}). Consumers must
 * treat payload entries as JSON values (for example {@code String.valueOf(value)} or
 * {@link Number#longValue()}), not cast them to the producer's Java type. Events read from an
 * in-memory store may still carry the producer's original types.
 */
public interface SessionEventStore {

    void append(SessionEvent event);

    /** Appends a batch in order; adapters that can batch override this for one round trip. */
    default void appendAll(List<SessionEvent> events) {
        events.forEach(this::append);
    }

    List<SessionEvent> after(SessionId id, long seq, int limit);

    long maxSeq(SessionId id);
}
