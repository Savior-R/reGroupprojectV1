package com.shijiangtai.m3classroom.domain.recording;

import java.util.Objects;
import java.util.UUID;

public record RecordingStream(
        UUID id,
        UUID recordingId,
        StreamType type,
        String uploadId,
        String objectKey,
        long partSize,
        int partsUploaded,
        long bytesUploaded,
        Long durationMs,
        long offsetMs,
        String mime,
        StreamStatus status) {

    public static final long DEFAULT_PART_SIZE = 5L * 1024 * 1024;

    public RecordingStream {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(recordingId, "recordingId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        if (partSize <= 0) {
            throw new IllegalArgumentException("partSize must be positive");
        }
        if (partsUploaded < 0) {
            throw new IllegalArgumentException("partsUploaded must not be negative");
        }
        if (bytesUploaded < 0) {
            throw new IllegalArgumentException("bytesUploaded must not be negative");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        if (offsetMs < 0) {
            throw new IllegalArgumentException("offsetMs must not be negative");
        }
    }

    RecordingStream uploaded(
            int partsUploaded, long bytesUploaded, long durationMs, String mime) {
        return new RecordingStream(id, recordingId, type, uploadId, objectKey, partSize,
                partsUploaded, bytesUploaded, durationMs, offsetMs, mime, StreamStatus.UPLOADED);
    }

    RecordingStream withStatus(StreamStatus newStatus) {
        return new RecordingStream(id, recordingId, type, uploadId, objectKey, partSize,
                partsUploaded, bytesUploaded, durationMs, offsetMs, mime, newStatus);
    }

    RecordingStream withOffset(long newOffsetMs) {
        return new RecordingStream(id, recordingId, type, uploadId, objectKey, partSize,
                partsUploaded, bytesUploaded, durationMs, newOffsetMs, mime, status);
    }
}
