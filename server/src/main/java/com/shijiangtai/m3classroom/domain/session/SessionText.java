package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.shared.domain.SessionId;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of the unified session text stream ({@code session_texts}, 01 §"session_texts").
 *
 * <p>Teacher ASR, generated student speech and structured-interview text share this shape;
 * events reference the text through {@code text_id} instead of embedding the full text.
 */
public record SessionText(
        UUID id,
        SessionId sessionId,
        String speakerType,
        UUID speakerRef,
        String source,
        String kind,
        String text,
        Long startMs,
        Long endMs,
        long atMs,
        BigDecimal confidence,
        String modelVersion,
        String promptVersion,
        String status) {

    public SessionText {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(speakerType, "speakerType");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(status, "status");
        if (atMs < 0) {
            throw new IllegalArgumentException("atMs must not be negative");
        }
    }
}
