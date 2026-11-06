package com.shijiangtai.m3classroom.application.command;

import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.PartEtag;
import java.util.List;

/**
 * Completion payload for one recording stream's multipart upload.
 *
 * <p>{@code offsetMs} is the stream start relative to the session's recording t0 (the earliest
 * stream start of the attempt), as agreed in 02 §4.3 / 05B §4.
 */
public record CompletePartCommand(
        List<PartEtag> parts, long durationMs, long size, String mime, long offsetMs) {

    public CompletePartCommand {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /** Backward-compatible overload for callers that do not report an offset yet. */
    public CompletePartCommand(List<PartEtag> parts, long durationMs, long size, String mime) {
        this(parts, durationMs, size, mime, 0L);
    }
}
