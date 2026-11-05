package com.shijiangtai.m3classroom.application.view;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Replay manifest for one session (05B §5). */
public record ReplayView(
        UUID sessionId,
        String mode,
        long durationMs,
        List<ReplayStreamView> streams) {

    public ReplayView {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(mode, "mode");
        streams = List.copyOf(streams);
    }
}
