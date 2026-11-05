package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.shared.domain.SessionId;
import java.util.Objects;

/** Signals that the pending event/text writes for a session could not be drained. */
public final class PersistenceFlushException extends RuntimeException {

    private final SessionId sessionId;

    public PersistenceFlushException(SessionId sessionId, String message) {
        this(sessionId, message, null);
    }

    public PersistenceFlushException(SessionId sessionId, String message, Throwable cause) {
        super(message, cause);
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public SessionId sessionId() {
        return sessionId;
    }
}
