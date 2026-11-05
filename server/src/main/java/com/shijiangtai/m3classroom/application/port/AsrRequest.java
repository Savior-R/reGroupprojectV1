package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.shared.domain.SessionId;
import java.util.Objects;

/** Parameters of one streaming ASR connection. */
public record AsrRequest(SessionId sessionId, int sampleRate, String format, String language) {

    public AsrRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(language, "language");
    }
}
