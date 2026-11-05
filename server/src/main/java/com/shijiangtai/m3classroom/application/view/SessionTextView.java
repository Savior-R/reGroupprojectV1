package com.shijiangtai.m3classroom.application.view;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** One row of the unified session text stream (05B §7.5). */
public record SessionTextView(
        UUID id,
        String speakerType,
        UUID speakerRef,
        String source,
        String kind,
        String text,
        Long startMs,
        Long endMs,
        long atMs,
        BigDecimal confidence,
        String status) {

    public SessionTextView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(speakerType, "speakerType");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(status, "status");
    }
}
