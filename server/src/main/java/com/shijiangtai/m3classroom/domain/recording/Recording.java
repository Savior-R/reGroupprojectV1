package com.shijiangtai.m3classroom.domain.recording;

import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Recording aggregate for one session attempt.
 *
 * <p>Streams are kept in a {@link LinkedHashMap} pre-seeded in the canonical
 * camera, screen, audio order, so {@link #streams()} is stable regardless of the
 * order in which the browser starts the streams.
 *
 * <p>The aggregate does not store the task mode. Every transition that must know
 * which streams are required receives the session mode from the caller; this keeps
 * rehydration independent of the session row while still enforcing the required
 * stream rules at each transition.
 */
public final class Recording {
    private final UUID id;
    private final SessionId sessionId;
    private final LinkedHashMap<StreamType, RecordingStream> streams;
    private final Instant createdAt;
    private RecordingStatus status;
    private Instant updatedAt;

    private Recording(
            UUID id,
            SessionId sessionId,
            RecordingStatus status,
            List<RecordingStream> streams,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.streams = new LinkedHashMap<>();
        for (StreamType type : StreamType.values()) {
            this.streams.put(type, null);
        }
        for (RecordingStream stream : streams) {
            if (this.streams.get(stream.type()) != null) {
                throw new IllegalArgumentException("duplicate stream type: " + stream.type());
            }
            this.streams.put(stream.type(), stream);
        }
    }

    public static Recording create(UUID id, SessionId sessionId, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(now, "now");
        return new Recording(id, sessionId, RecordingStatus.RECORDING, List.of(), now, now);
    }

    public static Recording rehydrate(
            UUID id,
            SessionId sessionId,
            RecordingStatus status,
            List<RecordingStream> streams,
            Instant createdAt,
            Instant updatedAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(streams, "streams");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        for (RecordingStream stream : streams) {
            Objects.requireNonNull(stream, "stream");
            if (!id.equals(stream.recordingId())) {
                throw new IllegalArgumentException("stream does not belong to recording");
            }
        }
        return new Recording(id, sessionId, status, streams, createdAt, updatedAt);
    }

    public RecordingStream startStream(
            UUID streamId,
            TaskMode mode,
            StreamType type,
            String uploadId,
            String objectKey,
            long partSize,
            long offsetMs,
            String mime,
            Instant now) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(now, "now");
        requireRequiredStream("recording.start_stream", mode, type);
        requireRecordingStatus("startStream", RecordingStatus.RECORDING, RecordingStatus.UPLOADING);
        if (streams.get(type) != null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "录制流已存在",
                    Map.of("stream_type", type.wireValue()));
        }
        RecordingStream stream = new RecordingStream(streamId, id, type, uploadId, objectKey,
                partSize, 0, 0L, null, offsetMs, mime, StreamStatus.UPLOADING);
        streams.put(type, stream);
        this.status = RecordingStatus.UPLOADING;
        this.updatedAt = now;
        return stream;
    }

    public void markStreamUploaded(
            StreamType type,
            TaskMode mode,
            int partsUploaded,
            long bytesUploaded,
            long durationMs,
            String mime,
            Instant now) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(now, "now");
        requireRequiredStream("recording.complete_stream", mode, type);
        requireRecordingStatus("markStreamUploaded",
                RecordingStatus.RECORDING, RecordingStatus.UPLOADING);
        RecordingStream stream = requireStream(type);
        requireStreamStatus("markStreamUploaded", stream, StreamStatus.UPLOADING);
        streams.put(type, stream.uploaded(partsUploaded, bytesUploaded, durationMs, mime));
        this.updatedAt = now;
        recomputeStatus(mode);
    }

    /**
     * Records the stream start offset relative to the attempt's t0. The offset is supplied by the
     * browser at complete time (02 §4.3); replay uses the persisted value to align the streams.
     */
    public void updateStreamOffset(StreamType type, long offsetMs, Instant now) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(now, "now");
        if (offsetMs < 0) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "录制偏移不能为负",
                    Map.of("field", "offset_ms"));
        }
        RecordingStream stream = requireStream(type);
        if (stream.offsetMs() == offsetMs) {
            return;
        }
        streams.put(type, stream.withOffset(offsetMs));
        this.updatedAt = now;
    }

    public void markStreamFailed(StreamType type, Instant now) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(now, "now");
        RecordingStream stream = requireStream(type);
        requireStreamStatus("markStreamFailed", stream, StreamStatus.UPLOADING);
        streams.put(type, stream.withStatus(StreamStatus.FAILED));
        this.status = RecordingStatus.FAILED;
        this.updatedAt = now;
    }

    /**
     * Aborts an in-progress multipart upload. The stream becomes {@code failed} and
     * the recording fails with it. Aborting an unknown or already failed/deleted
     * stream is a no-op so the multipart abort endpoint can be retried.
     */
    public void abortStream(StreamType type, Instant now) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(now, "now");
        RecordingStream stream = streams.get(type);
        if (stream == null
                || stream.status() == StreamStatus.FAILED
                || stream.status() == StreamStatus.DELETED) {
            return;
        }
        requireStreamStatus("abortStream", stream, StreamStatus.UPLOADING);
        streams.put(type, stream.withStatus(StreamStatus.FAILED));
        this.status = RecordingStatus.FAILED;
        this.updatedAt = now;
    }

    /**
     * Marks the whole recording and every stream as deleted. Used by session cancellation, where
     * the temporary rows are kept but the aggregate and its objects are no longer readable.
     */
    public void markDeleted(Instant now) {
        Objects.requireNonNull(now, "now");
        for (RecordingStream stream : streams()) {
            streams.put(stream.type(), stream.withStatus(StreamStatus.DELETED));
        }
        this.status = RecordingStatus.DELETED;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public RecordingStatus status() {
        return status;
    }

    public List<RecordingStream> streams() {
        return streams.values().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    public Optional<RecordingStream> stream(StreamType type) {
        Objects.requireNonNull(type, "type");
        return Optional.ofNullable(streams.get(type));
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private void recomputeStatus(TaskMode mode) {
        EnumSet<StreamType> uploaded = EnumSet.noneOf(StreamType.class);
        for (RecordingStream stream : streams.values()) {
            if (stream == null) {
                continue;
            }
            if (stream.status() == StreamStatus.FAILED) {
                this.status = RecordingStatus.FAILED;
                return;
            }
            if (stream.status() == StreamStatus.UPLOADED) {
                uploaded.add(stream.type());
            }
        }
        this.status = RequiredStreams.missing(uploaded, mode).isEmpty()
                ? RecordingStatus.UPLOADED
                : RecordingStatus.UPLOADING;
    }

    private void requireRequiredStream(String action, TaskMode mode, StreamType type) {
        if (!RequiredStreams.forMode(mode).contains(type)) {
            throw new DomainException(ErrorCode.SESSION_STATE_INVALID, "录制流不适用于当前模式",
                    Map.of("action", action,
                            "mode", mode.wireValue(),
                            "stream_type", type.wireValue()));
        }
    }

    private RecordingStream requireStream(StreamType type) {
        RecordingStream stream = streams.get(type);
        if (stream == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "录制流不存在",
                    Map.of("stream_type", type.wireValue()));
        }
        return stream;
    }

    private void requireStreamStatus(
            String action, RecordingStream stream, StreamStatus expected) {
        if (stream.status() != expected) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "录制流状态不允许该操作",
                    Map.of("action", action,
                            "stream_type", stream.type().wireValue(),
                            "status", stream.status().wireValue()));
        }
    }

    private void requireRecordingStatus(String action, RecordingStatus... allowed) {
        for (RecordingStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new DomainException(ErrorCode.VALIDATION_ERROR, "录制状态不允许该操作",
                Map.of("action", action, "status", status.wireValue()));
    }
}
