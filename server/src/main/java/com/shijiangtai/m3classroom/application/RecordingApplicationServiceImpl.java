package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.command.CompletePartCommand;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.ObjectStat;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.PartEtag;
import com.shijiangtai.m3classroom.application.view.MultipartUploadView;
import com.shijiangtai.m3classroom.application.view.PartUrlView;
import com.shijiangtai.m3classroom.application.view.RecordingStreamView;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingObjectKeyBuilder;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStatus;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.RequiredStreams;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording multipart application service (05B §4).
 *
 * <p>The browser records each stream locally and uploads 5 MB parts straight to MinIO through
 * presigned URLs. Java owns the aggregate transitions, object keys and the complete/abort
 * bookkeeping; it never receives media bytes.
 */
@Service
public class RecordingApplicationServiceImpl implements RecordingApplicationService {

    private static final Logger log =
            LoggerFactory.getLogger(RecordingApplicationServiceImpl.class);
    private static final Duration PART_URL_TTL = Duration.ofMinutes(15);
    private static final int MAX_PART_NUMBER = 10_000;
    private static final String VIDEO_MIME = "video/webm";
    private static final String AUDIO_MIME = "audio/webm";

    private final SessionRepository sessionRepository;
    private final TaskRepository taskRepository;
    private final RecordingRepository recordingRepository;
    private final RecordingObjectStorage storage;
    private final RecordingAccessPolicy accessPolicy;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;
    private final RecordingObjectKeyBuilder objectKeyBuilder = new RecordingObjectKeyBuilder();

    public RecordingApplicationServiceImpl(
            SessionRepository sessionRepository,
            TaskRepository taskRepository,
            RecordingRepository recordingRepository,
            RecordingObjectStorage storage,
            RecordingAccessPolicy accessPolicy,
            IdGenerator idGenerator,
            ClockProvider clockProvider) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.recordingRepository = Objects.requireNonNull(
                recordingRepository, "recordingRepository");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clockProvider = Objects.requireNonNull(clockProvider, "clockProvider");
    }

    @Override
    @Transactional
    public MultipartUploadView start(
            CurrentUser student, SessionId sessionId, StreamType type) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        Session session = findSession(sessionId);
        accessPolicy.requireUpload(student, session);
        requireRecordableStatus(session);
        requireRequiredStream(session, type);

        Recording recording = recordingRepository.findBySession(sessionId)
                .orElseGet(() -> Recording.create(
                        idGenerator.nextId(), sessionId, clockProvider.now()));
        RecordingStream existing = recording.stream(type).orElse(null);
        if (existing != null) {
            // A retried start (for example after a lost response) returns the stored coordinates.
            if (existing.status() == StreamStatus.UPLOADING && existing.uploadId() != null) {
                return new MultipartUploadView(
                        existing.uploadId(), existing.objectKey(), existing.partSize());
            }
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "录制流已存在",
                    Map.of("stream_type", type.wireValue(),
                            "status", existing.status().wireValue()));
        }
        requireStartableRecording(recording);

        Task task = taskRepository.findById(session.taskId())
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "任务不存在", Map.of("task_id", session.taskId().value())));
        TaskSnapshot snapshot = task.snapshot();
        if (snapshot == null) {
            throw new DomainException(ErrorCode.TASK_CONFIG_INVALID, "任务快照不存在",
                    Map.of("task_id", session.taskId().value().toString()));
        }
        String objectKey = objectKeyBuilder.taskKey(
                snapshot.schoolId(), session.taskId().value(), sessionId.value(), type);
        String mime = mimeFor(type);
        RecordingObjectStorage.MultipartUpload upload =
                storage.createMultipart(objectKey, mime);
        try {
            long offsetMs = session.streamOffsets().getOrDefault(type, 0L);
            recording.startStream(
                    idGenerator.nextId(), session.mode(), type,
                    upload.uploadId(), upload.objectKey(), upload.partSize(),
                    offsetMs, mime, clockProvider.now());
            recordingRepository.save(recording);
        } catch (RuntimeException exception) {
            // The aggregate rejected the transition or the row could not be saved. Do not leave
            // an unreferenced multipart upload behind.
            abortCreatedMultipart(upload);
            throw exception;
        }
        return new MultipartUploadView(
                upload.uploadId(), upload.objectKey(), upload.partSize());
    }

    @Override
    @Transactional(readOnly = true)
    public PartUrlView partUrl(
            CurrentUser student, SessionId sessionId, StreamType type, int partNumber) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        Session session = findSession(sessionId);
        accessPolicy.requireUpload(student, session);
        requirePartNumber(partNumber);
        RecordingStream stream = requireStream(requireRecording(sessionId), type);
        requireUploading(stream);
        String url = storage.presignPart(
                stream.objectKey(), stream.uploadId(), partNumber, PART_URL_TTL);
        return new PartUrlView(url, partNumber, PART_URL_TTL.toSeconds());
    }

    /**
     * The multipart is consumed before the size/stat/save checks. Those checks transition the
     * stream to failed and then throw, so the failure transition must commit together with the
     * thrown {@link DomainException} (same pattern as the session end path).
     */
    @Override
    @Transactional(noRollbackFor = DomainException.class)
    public RecordingStreamView complete(
            CurrentUser student, SessionId sessionId, StreamType type,
            CompletePartCommand command) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(command, "command");
        Session session = findSession(sessionId);
        accessPolicy.requireUpload(student, session);
        Recording recording = requireRecording(sessionId);
        RecordingStream stream = requireStream(recording, type);
        if (stream.status() == StreamStatus.UPLOADED) {
            // A retried complete after a lost response is a no-op returning the stored metadata.
            return toView(stream);
        }
        requireUploading(stream);
        requireValidParts(command.parts());
        requireValidSize(command);

        storage.completeMultipart(
                stream.objectKey(), stream.uploadId(), command.parts());
        Instant now = clockProvider.now();
        ObjectStat stat;
        try {
            stat = storage.stat(stream.objectKey());
        } catch (RuntimeException exception) {
            // The multipart is already consumed; fail the stream so a fresh start is possible.
            markStreamFailed(sessionId, type, now);
            throw exception;
        }
        if (stat.size() < command.size()) {
            markStreamFailed(sessionId, type, now);
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "上传对象大小不一致",
                    Map.of("object_key", stream.objectKey(),
                            "expected_size", command.size(),
                            "actual_size", stat.size()));
        }

        try {
            recording.markStreamUploaded(
                    type, session.mode(), command.parts().size(),
                    command.size(), command.durationMs(), command.mime(), now);
            recordingRepository.save(recording);
        } catch (RuntimeException exception) {
            markStreamFailed(sessionId, type, now);
            throw exception;
        }
        return toView(recording.stream(type).orElseThrow());
    }

    @Override
    @Transactional
    public void abort(CurrentUser student, SessionId sessionId, StreamType type) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        Session session = findSession(sessionId);
        accessPolicy.requireUpload(student, session);
        Recording recording = recordingRepository.findBySession(sessionId).orElse(null);
        if (recording == null) {
            return;
        }
        RecordingStream stream = recording.stream(type).orElse(null);
        if (stream == null || stream.status() != StreamStatus.UPLOADING
                || stream.uploadId() == null) {
            return;
        }
        try {
            storage.abortMultipart(stream.objectKey(), stream.uploadId());
        } catch (RuntimeException exception) {
            // Abort is idempotent and best effort: the upload may already be gone or MinIO may
            // be unreachable. The aggregate still moves to failed so the client can retry safely.
            log.warn("Aborting recording multipart failed: session={}, stream={}",
                    sessionId.value(), type.wireValue(), exception);
        }
        recording.abortStream(type, clockProvider.now());
        recordingRepository.save(recording);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecordingStreamView> list(CurrentUser user, SessionId sessionId) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(sessionId, "sessionId");
        Session session = findSession(sessionId);
        accessPolicy.requireRead(user, session);
        return recordingRepository.findBySession(sessionId)
                .map(recording -> recording.streams().stream()
                        .map(RecordingApplicationServiceImpl::toView)
                        .toList())
                .orElseGet(List::of);
    }

    private Session findSession(SessionId sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "会话不存在", Map.of("session_id", sessionId.value())));
    }

    private Recording requireRecording(SessionId sessionId) {
        return recordingRepository.findBySession(sessionId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "录制不存在", Map.of("session_id", sessionId.value())));
    }

    private static RecordingStream requireStream(Recording recording, StreamType type) {
        return recording.stream(type)
                .orElseThrow(() -> new DomainException(ErrorCode.VALIDATION_ERROR,
                        "录制流不存在", Map.of("stream_type", type.wireValue())));
    }

    private static void requireUploading(RecordingStream stream) {
        if (stream.status() != StreamStatus.UPLOADING || stream.uploadId() == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "录制流状态不允许该操作",
                    Map.of("stream_type", stream.type().wireValue(),
                            "status", stream.status().wireValue()));
        }
    }

    private static void requireStartableRecording(Recording recording) {
        if (recording.status() != RecordingStatus.RECORDING
                && recording.status() != RecordingStatus.UPLOADING) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "录制状态不允许开始新的上传",
                    Map.of("status", recording.status().wireValue()));
        }
    }

    private void abortCreatedMultipart(RecordingObjectStorage.MultipartUpload upload) {
        try {
            storage.abortMultipart(upload.objectKey(), upload.uploadId());
        } catch (RuntimeException exception) {
            log.warn("Aborting orphan recording multipart failed: objectKey={}",
                    upload.objectKey(), exception);
        }
    }

    private void markStreamFailed(SessionId sessionId, StreamType type, Instant now) {
        Recording current = recordingRepository.findBySession(sessionId).orElse(null);
        if (current == null) {
            return;
        }
        RecordingStream stream = current.stream(type).orElse(null);
        if (stream == null || stream.status() != StreamStatus.UPLOADING) {
            return;
        }
        current.abortStream(type, now);
        try {
            recordingRepository.save(current);
        } catch (RuntimeException exception) {
            log.warn("Marking failed recording stream could not be saved: session={}, stream={}",
                    sessionId.value(), type.wireValue(), exception);
        }
    }

    private static void requireRecordableStatus(Session session) {
        if (session.status() != SessionStatus.PREPARING
                && session.status() != SessionStatus.IN_PROGRESS) {
            throw new DomainException(ErrorCode.SESSION_STATE_INVALID, "当前状态不允许开始录制",
                    Map.of("action", "recording.start",
                            "status", session.status().wireValue()));
        }
    }

    private static void requireRequiredStream(Session session, StreamType type) {
        if (!RequiredStreams.forMode(session.mode()).contains(type)) {
            throw new DomainException(ErrorCode.SESSION_STATE_INVALID, "录制流不适用于当前模式",
                    Map.of("action", "recording.start",
                            "mode", session.mode().wireValue(),
                            "stream_type", type.wireValue()));
        }
    }

    private static void requirePartNumber(int partNumber) {
        if (partNumber < 1 || partNumber > MAX_PART_NUMBER) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "分片编号超出范围",
                    Map.of("field", "part_number", "value", partNumber));
        }
    }

    private static void requireValidParts(List<PartEtag> parts) {
        if (parts.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "上传分片不能为空",
                    Map.of("field", "parts"));
        }
        for (PartEtag part : parts) {
            if (part == null || part.partNumber() < 1 || part.partNumber() > MAX_PART_NUMBER) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR, "分片编号超出范围",
                        Map.of("field", "parts"));
            }
            if (part.etag() == null || part.etag().isBlank()) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR, "分片 etag 不能为空",
                        Map.of("field", "parts"));
            }
        }
    }

    private static void requireValidSize(CompletePartCommand command) {
        if (command.size() < 0) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "录制大小不能为负",
                    Map.of("field", "size", "value", command.size()));
        }
        if (command.durationMs() < 0) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "录制时长不能为负",
                    Map.of("field", "duration_ms", "value", command.durationMs()));
        }
    }

    private static String mimeFor(StreamType type) {
        return type == StreamType.AUDIO ? AUDIO_MIME : VIDEO_MIME;
    }

    private static RecordingStreamView toView(RecordingStream stream) {
        return new RecordingStreamView(
                stream.type().wireValue(),
                stream.status().wireValue(),
                stream.uploadId(),
                stream.objectKey(),
                stream.partsUploaded(),
                stream.bytesUploaded(),
                stream.durationMs(),
                stream.offsetMs(),
                stream.mime(),
                stream.partSize());
    }
}
