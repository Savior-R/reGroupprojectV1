package com.shijiangtai.m3classroom.application;

import com.shijiangtai.shared.domain.SessionId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of live {@link SessionActor}s, one per active session.
 *
 * <p>Task 10 adds {@code getOrCreate} when actor construction is wired to the runtime ports.
 */
@Component
public final class SessionRuntimeRegistry {

    private final Map<SessionId, SessionActor> actors = new ConcurrentHashMap<>();

    public void register(SessionActor actor) {
        Objects.requireNonNull(actor, "actor");
        actors.put(actor.session().id(), actor);
    }

    public Optional<SessionActor> find(SessionId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(actors.get(id));
    }

    public void remove(SessionId id) {
        Objects.requireNonNull(id, "id");
        actors.remove(id);
    }
}
