package com.shijiangtai.m3classroom.application.command;

import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.PartEtag;
import java.util.List;

/** Completion payload for one recording stream's multipart upload. */
public record CompletePartCommand(List<PartEtag> parts, long durationMs, long size, String mime) {

    public CompletePartCommand {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
