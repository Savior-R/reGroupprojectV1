package com.shijiangtai.m3classroom.domain.session;

import java.util.Set;

/**
 * Event types that carry control-plane state only and have no replay value (05B §7.1).
 *
 * <p>The replay query excludes these before applying its limit, so a run of ticks cannot produce
 * an empty page or starve the client's pagination. The control WebSocket uses the same set when
 * replaying a resume tail, except that {@code session.resumed} itself is produced after that tail.
 */
public final class ReplayEventTypes {

    public static final Set<String> NON_REPLAYABLE = Set.of(
            "session.time_tick", "session.resumed", "ping", "pong", "time.sync");

    private ReplayEventTypes() {
    }
}
