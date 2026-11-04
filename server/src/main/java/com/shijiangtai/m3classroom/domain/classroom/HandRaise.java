package com.shijiangtai.m3classroom.domain.classroom;

import java.util.Objects;
import java.util.UUID;

/**
 * One selected hand raise in a qa hand window (05A §5.4).
 *
 * <p>{@code questionPreview} is an optional, UI-only hint; the authoritative question text lives
 * in {@code session_texts} and is attached by the content-generation layer. It may be
 * {@code null} at selection time.
 */
public record HandRaise(UUID handRaiseId, UUID studentId, String questionPreview) {

    public HandRaise {
        Objects.requireNonNull(handRaiseId, "handRaiseId");
        Objects.requireNonNull(studentId, "studentId");
    }
}
