package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.command.CompletePartCommand;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.m3classroom.application.view.MultipartUploadView;
import com.shijiangtai.m3classroom.application.view.PartUrlView;
import com.shijiangtai.m3classroom.application.view.RecordingStreamView;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.shared.application.CurrentUser;
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
 *
 * <p>Deadlock invariant: the row-locked aggregate mutation lives in {@link
 * RecordingMutationService}, whose transaction has already completed (committed or rolled back)
 * when this class performs the session-failure transition. A {@link SessionActor#fail} call or a
 * {@link RecordingSessionFailureService} write therefore never runs while this thread holds the
 * session row lock. The reverse direction — the actor monitor held during a terminal save — can
 * wait for the row lock without forming a cycle.
 */
@Service
public class RecordingApplicationServiceImpl implements RecordingApplicationService {

    private static final Logger log =
            LoggerFactory.getLogger(RecordingApplicationServiceImpl.class);
    private static final Duration PART_URL_TTL = Duration.ofMinutes(15);
    private static final int MAX_PART_NUMBER = 10_000;

    private final SessionRepository sessionRepository;
    private final RecordingRepository recordingRepository;
    private final RecordingObjectStorage storage;
    private final RecordingAccessPolicy accessPolicy;
    private final SessionRuntimeRegistry runtimeRegistry;
    private final RecordingMutationService mutationService;
    private final RecordingSessionFailureService sessionFailureService;

    public RecordingApplicationServiceImpl(
            SessionRepository sessionRepository,
            RecordingRepository recordingRepository,
            RecordingObjectStorage storage,
            RecordingAccessPolicy accessPolicy,
            SessionRuntimeRegistry runtimeRegistry,
            RecordingMutationService mutationService,
            RecordingSessionFailureService sessionFailureService) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.recordingRepository = Objects.requireNonNull(
                recordingRepository, "recordingRepository");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService");
        this.sessionFailureService = Objects.requireNonNull(
                sessionFailureService, "sessionFailureService");
    }

    @Override
    public MultipartUploadView start(
            CurrentUser student, SessionId sessionId, StreamType type) {
        return mutationService.start(student, sessionId, type);
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

    @Override
    public RecordingStreamView complete(
            CurrentUser student, SessionId sessionId, StreamType type,
            CompletePartCommand command) {
        try {
            return mutationService.complete(student, sessionId, type, command);
        } catch (RuntimeException exception) {
            throw afterRecordingFailure(sessionId, exception);
        }
    }

    @Override
    public void abort(CurrentUser student, SessionId sessionId, StreamType type) {
        Instant failureTime;
        try {
            failureTime = mutationService.abort(student, sessionId, type).orElse(null);
        } catch (RuntimeException exception) {
            throw afterRecordingFailure(sessionId, exception);
        }
        if (failureTime != null) {
            failSessionOnRecordingLoss(sessionId, failureTime);
        }
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
                        .map(RecordingMutationService::toView)
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * Runs after the recording mutation transaction has ended. A recording-loss failure is
     * reported only for post-multipart failures; pre-consumption validation returns the original
     * exception untouched so the session stays retryable.
     */
    private RuntimeException afterRecordingFailure(
            SessionId sessionId, RuntimeException exception) {
        if (exception instanceof RecordingMutationService.RecordingLostFailure failure) {
            failSessionOnRecordingLoss(sessionId, failure.failureTime());
            return failure.cause();
        }
        return exception;
    }

    /**
     * Fails the session with {@link FailureReason#RECORDING_LOST}. The live actor, when present,
     * appends {@code session.failed} and persists the terminal state through its status listener;
     * without an actor the new transaction in {@link RecordingSessionFailureService} re-reads the
     * session and writes the terminal state plus the event.
     *
     * <p>{@code submitted}/{@code cancelled}/{@code failed} sessions are never changed.
     */
    private void failSessionOnRecordingLoss(SessionId sessionId, Instant now) {
        SessionActor actor = runtimeRegistry.find(sessionId).orElse(null);
        if (actor != null) {
            try {
                actor.fail(FailureReason.RECORDING_LOST);
                return;
            } catch (RuntimeException exception) {
                log.warn("Live actor could not fail session {}; persisting directly",
                        sessionId.value(), exception);
            }
        }
        try {
            sessionFailureService.failDirectly(sessionId, now);
        } catch (RuntimeException exception) {
            log.warn("Recording failure could not fail session {}", sessionId.value(),
                    exception);
        }
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

    private static void requirePartNumber(int partNumber) {
        if (partNumber < 1 || partNumber > MAX_PART_NUMBER) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "分片编号超出范围",
                    Map.of("field", "part_number", "value", partNumber));
        }
    }
}