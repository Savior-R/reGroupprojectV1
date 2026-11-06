package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.SCHOOL_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER;
import static com.shijiangtai.m3classroom.TestFixtures.admin;
import static com.shijiangtai.m3classroom.TestFixtures.course;
import static com.shijiangtai.m3classroom.TestFixtures.coursePort;
import static com.shijiangtai.m3classroom.TestFixtures.freeDraft;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.student2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shijiangtai.m3classroom.application.command.CompletePartCommand;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.ObjectStat;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.PartEtag;
import com.shijiangtai.m3classroom.application.view.MultipartUploadView;
import com.shijiangtai.m3classroom.application.view.PartUrlView;
import com.shijiangtai.m3classroom.application.view.RecordingStreamView;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStatus;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.FailureReason;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.RubricWeights;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryRecordingObjectStorage;
import com.shijiangtai.m3classroom.support.InMemorySessionEventStore;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.m3classroom.support.InMemorySubmissionRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecordingApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final long PART_SIZE = 5L * 1024 * 1024;

    private InMemoryTaskRepository tasks;
    private InMemorySessionRepository sessions;
    private InMemoryRecordingRepository recordings;
    private InMemoryRecordingObjectStorage storage;
    private InMemorySessionEventStore events;
    private SessionRuntimeRegistry runtimeRegistry;
    private RecordingAccessPolicy accessPolicy;
    private RecordingApplicationService service;
    private TaskId taskId;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        sessions = new InMemorySessionRepository();
        recordings = new InMemoryRecordingRepository();
        storage = new InMemoryRecordingObjectStorage();
        events = new InMemorySessionEventStore();
        runtimeRegistry = mock(SessionRuntimeRegistry.class);
        when(runtimeRegistry.find(any())).thenReturn(Optional.empty());
        accessPolicy = new RecordingAccessPolicy(
                new SessionAccessPolicy(coursePort(), new InMemorySubmissionRepository()));
        service = newService(storage, recordings);
        taskId = publishTask(TaskMode.FREE);
    }

    private RecordingApplicationService newService(
            RecordingObjectStorage objectStorage, RecordingRepository repository) {
        return new RecordingApplicationServiceImpl(
                sessions, tasks, repository, objectStorage, accessPolicy,
                runtimeRegistry, events, UUID::randomUUID, () -> NOW);
    }

    @Test
    void startCreatesMultipartForRequiredStream() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);

        MultipartUploadView view = service.start(student(), sessionId, StreamType.CAMERA);

        assertThat(view.uploadId()).isNotBlank();
        assertThat(view.partSize()).isEqualTo(PART_SIZE);
        assertThat(view.objectKey()).isEqualTo(
                "recordings/" + SCHOOL_ID + "/task/" + taskId.value() + "/" + sessionId.value()
                        + "/camera.webm");
        RecordingStream stream = recording(sessionId).stream(StreamType.CAMERA).orElseThrow();
        assertThat(stream.status()).isEqualTo(StreamStatus.UPLOADING);
        assertThat(stream.uploadId()).isEqualTo(view.uploadId());
        assertThat(stream.objectKey()).isEqualTo(view.objectKey());
        assertThat(stream.offsetMs()).isZero();
        assertThat(stream.mime()).isEqualTo("video/webm");
        assertThat(recording(sessionId).status()).isEqualTo(RecordingStatus.UPLOADING);
    }

    @Test
    void startIsIdempotentWhileStreamIsUploading() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);

        MultipartUploadView first = service.start(student(), sessionId, StreamType.CAMERA);
        MultipartUploadView second = service.start(student(), sessionId, StreamType.CAMERA);

        assertThat(second).isEqualTo(first);
        assertThat(recording(sessionId).streams()).hasSize(1);
    }

    @Test
    void startRejectsScreenForInterview() {
        TaskId interviewTaskId = publishTask(TaskMode.INTERVIEW);
        SessionId sessionId = createSession(
                interviewTaskId, TaskMode.INTERVIEW, SessionStatus.PREPARING);

        assertCode(() -> service.start(student(), sessionId, StreamType.SCREEN),
                ErrorCode.SESSION_STATE_INVALID);
        assertThat(recordings.findBySession(sessionId)).isEmpty();
    }

    @Test
    void startRejectsOtherStudentsSession() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);

        assertCode(() -> service.start(student2(), sessionId, StreamType.CAMERA),
                ErrorCode.FORBIDDEN);
        assertThat(recordings.findBySession(sessionId)).isEmpty();
    }

    @Test
    void startRejectsFinishedSession() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.ENDED);

        assertCode(() -> service.start(student(), sessionId, StreamType.CAMERA),
                ErrorCode.SESSION_STATE_INVALID);
    }

    @Test
    void partUrlRejectsPartNumberOutsideRange() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);
        service.start(student(), sessionId, StreamType.CAMERA);

        assertCode(() -> service.partUrl(student(), sessionId, StreamType.CAMERA, 0),
                ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.partUrl(student(), sessionId, StreamType.CAMERA, 10001),
                ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void partUrlReturnsPresignedUrlWithFifteenMinuteTtl() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);
        MultipartUploadView start = service.start(student(), sessionId, StreamType.CAMERA);

        PartUrlView view = service.partUrl(student(), sessionId, StreamType.CAMERA, 3);

        assertThat(view.partNumber()).isEqualTo(3);
        assertThat(view.expiresInSeconds()).isEqualTo(Duration.ofMinutes(15).toSeconds());
        assertThat(view.url()).contains(start.objectKey()).contains("partNumber=3");
    }

    @Test
    void completeMarksStreamUploadedAndRecordingUploadedWhenAllPresent() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);
        long size = 4096;
        RecordingStreamView last = null;
        for (StreamType type : StreamType.values()) {
            MultipartUploadView upload = service.start(student(), sessionId, type);
            storage.recordObjectSize(upload.objectKey(), size);
            last = service.complete(student(), sessionId, type,
                    new CompletePartCommand(
                            List.of(new PartEtag(1, "etag-1")), 5_000, size, "video/webm"));
        }

        assertThat(last).isNotNull();
        assertThat(last.streamType()).isEqualTo("audio");
        assertThat(last.status()).isEqualTo("uploaded");
        assertThat(last.partsUploaded()).isEqualTo(1);
        assertThat(last.bytesUploaded()).isEqualTo(size);
        assertThat(last.durationMs()).isEqualTo(5_000);
        assertThat(last.mime()).isEqualTo("video/webm");
        assertThat(last.partSize()).isEqualTo(PART_SIZE);
        assertThat(last.offsetMs()).isZero();

        Recording recording = recording(sessionId);
        assertThat(recording.status()).isEqualTo(RecordingStatus.UPLOADED);
        assertThat(recording.streams()).allMatch(
                stream -> stream.status() == StreamStatus.UPLOADED);
    }

    @Test
    void completeWithSizeMismatchFails() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);
        MultipartUploadView upload = service.start(student(), sessionId, StreamType.CAMERA);
        storage.recordObjectSize(upload.objectKey(), 10);

        assertThatThrownBy(() -> service.complete(student(), sessionId, StreamType.CAMERA,
                new CompletePartCommand(
                        List.of(new PartEtag(1, "etag-1")), 5_000, 20, "video/webm")))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(exception.details())
                            .containsEntry("expected_size", 20L)
                            .containsEntry("actual_size", 10L);
                });
        assertThat(recording(sessionId).stream(StreamType.CAMERA).orElseThrow().status())
                .isEqualTo(StreamStatus.FAILED);
        assertThat(recording(sessionId).status()).isEqualTo(RecordingStatus.FAILED);
    }

    @Test
    void completeRejectsZeroDurationAndFailsTheSession() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.IN_PROGRESS);
        MultipartUploadView upload = service.start(student(), sessionId, StreamType.CAMERA);
        storage.recordObjectSize(upload.objectKey(), 1024);

        assertCode(() -> service.complete(student(), sessionId, StreamType.CAMERA,
                        new CompletePartCommand(
                                List.of(new PartEtag(1, "etag-1")), 0, 1024, "video/webm")),
                ErrorCode.VALIDATION_ERROR);

        // A pre-multipart validation rejection stays retryable: nothing was consumed.
        assertThat(recording(sessionId).stream(StreamType.CAMERA).orElseThrow().status())
                .isEqualTo(StreamStatus.UPLOADING);
        assertThat(sessions.findById(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.IN_PROGRESS);
    }

    @Test
    void completeRejectsZeroSizeAndFailsTheSession() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.IN_PROGRESS);
        MultipartUploadView upload = service.start(student(), sessionId, StreamType.CAMERA);
        storage.recordObjectSize(upload.objectKey(), 0);

        assertCode(() -> service.complete(student(), sessionId, StreamType.CAMERA,
                        new CompletePartCommand(
                                List.of(new PartEtag(1, "etag-1")), 5_000, 0, "video/webm")),
                ErrorCode.VALIDATION_ERROR);

        // A pre-multipart validation rejection stays retryable: nothing was consumed.
        assertThat(recording(sessionId).stream(StreamType.CAMERA).orElseThrow().status())
                .isEqualTo(StreamStatus.UPLOADING);
        assertThat(sessions.findById(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.IN_PROGRESS);
    }

    @Test
    void completeRejectsIncompatibleStoredContentType() {
        RecordingObjectStorage mismatched = new MimeOverridingStorage(
                storage, "application/octet-stream");
        RecordingApplicationService mismatchService = newService(mismatched, recordings);
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.IN_PROGRESS);
        MultipartUploadView upload = mismatchService.start(
                student(), sessionId, StreamType.CAMERA);
        storage.recordObjectSize(upload.objectKey(), 1024);

        assertThatThrownBy(() -> mismatchService.complete(
                        student(), sessionId, StreamType.CAMERA,
                        new CompletePartCommand(
                                List.of(new PartEtag(1, "etag-1")), 5_000, 1024,
                                "video/webm")))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(exception.details())
                            .containsEntry("stream_type", "camera")
                            .containsEntry("actual_content_type", "application/octet-stream");
                });
        assertThat(recording(sessionId).stream(StreamType.CAMERA).orElseThrow().status())
                .isEqualTo(StreamStatus.FAILED);
    }

    @Test
    void audioAcceptContentTypeWithCodecsParameter() {
        RecordingObjectStorage codecAware = new MimeOverridingStorage(
                storage, "audio/webm; codecs=opus");
        RecordingApplicationService codecService = newService(codecAware, recordings);
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.IN_PROGRESS);
        MultipartUploadView upload = codecService.start(
                student(), sessionId, StreamType.AUDIO);
        storage.recordObjectSize(upload.objectKey(), 1024);

        RecordingStreamView view = codecService.complete(
                student(), sessionId, StreamType.AUDIO,
                new CompletePartCommand(
                        List.of(new PartEtag(1, "etag-1")), 5_000, 1024, "audio/webm"));

        assertThat(view.status()).isEqualTo("uploaded");
    }

    @Test
    void completeSizeMismatchFailsSessionWhileInProgress() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.IN_PROGRESS);
        MultipartUploadView upload = service.start(student(), sessionId, StreamType.CAMERA);
        storage.recordObjectSize(upload.objectKey(), 10);

        assertCode(() -> service.complete(student(), sessionId, StreamType.CAMERA,
                        new CompletePartCommand(
                                List.of(new PartEtag(1, "etag-1")), 5_000, 20, "video/webm")),
                ErrorCode.VALIDATION_ERROR);

        assertThat(sessions.findById(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void completeSizeMismatchAfterEndedFailsSession() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.ENDED);
        String objectKey = seedMultipartStream(sessionId, StreamType.CAMERA);
        storage.recordObjectSize(objectKey, 10);

        assertCode(() -> service.complete(student(), sessionId, StreamType.CAMERA,
                        new CompletePartCommand(
                                List.of(new PartEtag(1, "etag-1")), 5_000, 20, "video/webm")),
                ErrorCode.VALIDATION_ERROR);

        assertThat(sessions.findById(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void completeFailureDelegatesToLiveActorInsteadOfAppendingDirectly() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.IN_PROGRESS);
        MultipartUploadView upload = service.start(student(), sessionId, StreamType.CAMERA);
        storage.recordObjectSize(upload.objectKey(), 10);
        SessionActor actor = mock(SessionActor.class);
        when(runtimeRegistry.find(sessionId)).thenReturn(Optional.of(actor));

        assertCode(() -> service.complete(student(), sessionId, StreamType.CAMERA,
                        new CompletePartCommand(
                                List.of(new PartEtag(1, "etag-1")), 5_000, 20, "video/webm")),
                ErrorCode.VALIDATION_ERROR);

        verify(actor).fail(FailureReason.RECORDING_LOST);
        assertThat(events.all()).isEmpty();
    }

    @Test
    void abortFailsInProgressSession() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.IN_PROGRESS);
        service.start(student(), sessionId, StreamType.CAMERA);

        service.abort(student(), sessionId, StreamType.CAMERA);

        assertThat(sessions.findById(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void abortAfterEndedFailsSession() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.ENDED);
        seedMultipartStream(sessionId, StreamType.CAMERA);

        service.abort(student(), sessionId, StreamType.CAMERA);

        assertThat(sessions.findById(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void terminalSessionIsNeverFailedByStreamFailure() {
        for (SessionStatus terminal : List.of(
                SessionStatus.SUBMITTED, SessionStatus.CANCELLED, SessionStatus.FAILED)) {
            SessionId sessionId = createSession(taskId, TaskMode.FREE, terminal);
            String objectKey = seedMultipartStream(sessionId, StreamType.CAMERA);
            storage.recordObjectSize(objectKey, 10);

            assertCode(() -> service.complete(student(), sessionId, StreamType.CAMERA,
                            new CompletePartCommand(
                                    List.of(new PartEtag(1, "etag-1")), 5_000, 20,
                                    "video/webm")),
                    ErrorCode.VALIDATION_ERROR);

            assertThat(sessions.findById(sessionId).orElseThrow().status())
                    .isEqualTo(terminal);
        }
    }

    @Test
    void completeIsRetrySafeForAlreadyUploadedStream() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);
        MultipartUploadView upload = service.start(student(), sessionId, StreamType.CAMERA);
        storage.recordObjectSize(upload.objectKey(), 1024);
        CompletePartCommand command = new CompletePartCommand(
                List.of(new PartEtag(1, "etag-1")), 5_000, 1024, "video/webm");
        RecordingStreamView first = service.complete(
                student(), sessionId, StreamType.CAMERA, command);

        RecordingStreamView retried = service.complete(
                student(), sessionId, StreamType.CAMERA, command);

        assertThat(retried).isEqualTo(first);
    }

    @Test
    void completeMultipartFailureAbortsUploadAndFailsStreamAndSession() {
        FailingCompleteStorage failingStorage = new FailingCompleteStorage(storage);
        RecordingApplicationService failingService = newService(failingStorage, recordings);
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.IN_PROGRESS);
        failingService.start(student(), sessionId, StreamType.CAMERA);

        assertThatThrownBy(() -> failingService.complete(
                        student(), sessionId, StreamType.CAMERA,
                        new CompletePartCommand(
                                List.of(new PartEtag(1, "etag-1")), 5_000, 1024,
                                "video/webm")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("complete failed");

        assertThat(failingStorage.abortCalls()).isEqualTo(1);
        assertThat(recording(sessionId).stream(StreamType.CAMERA).orElseThrow().status())
                .isEqualTo(StreamStatus.FAILED);
        assertThat(sessions.findById(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void completeSaveFailureMarksStreamFailed() {
        FailingUploadedSaveRecordingRepository failingRepository =
                new FailingUploadedSaveRecordingRepository();
        RecordingApplicationService failingService = newService(storage, failingRepository);
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);
        MultipartUploadView upload = failingService.start(
                student(), sessionId, StreamType.CAMERA);
        storage.recordObjectSize(upload.objectKey(), 1024);

        assertThatThrownBy(() -> failingService.complete(
                student(), sessionId, StreamType.CAMERA,
                new CompletePartCommand(
                        List.of(new PartEtag(1, "etag-1")), 5_000, 1024, "video/webm")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recording row unavailable");

        Recording stored = failingRepository.findBySession(sessionId).orElseThrow();
        assertThat(stored.status()).isEqualTo(RecordingStatus.FAILED);
        assertThat(stored.stream(StreamType.CAMERA).orElseThrow().status())
                .isEqualTo(StreamStatus.FAILED);
    }

    @Test
    void startRejectsSessionFailedByStreamAbort() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);
        service.start(student(), sessionId, StreamType.CAMERA);
        service.abort(student(), sessionId, StreamType.CAMERA);

        assertThat(sessions.findById(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
        assertCode(() -> service.start(student(), sessionId, StreamType.SCREEN),
                ErrorCode.SESSION_STATE_INVALID);
    }

    @Test
    void abortMarksStreamFailedAndIsIdempotent() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);
        MultipartUploadView upload = service.start(student(), sessionId, StreamType.CAMERA);

        service.abort(student(), sessionId, StreamType.CAMERA);
        service.abort(student(), sessionId, StreamType.CAMERA);

        RecordingStream stream = recording(sessionId).stream(StreamType.CAMERA).orElseThrow();
        assertThat(stream.status()).isEqualTo(StreamStatus.FAILED);
        assertThat(recording(sessionId).status()).isEqualTo(RecordingStatus.FAILED);
        assertThatThrownBy(() -> storage.presignPart(
                upload.objectKey(), upload.uploadId(), 1, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void abortUnknownRecordingIsNoOp() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);

        service.abort(student(), sessionId, StreamType.AUDIO);

        assertThat(recordings.findBySession(sessionId)).isEmpty();
    }

    @Test
    void listReturnsOnlySessionStreamsForOwner() {
        SessionId sessionId = createSession(taskId, TaskMode.FREE, SessionStatus.PREPARING);
        service.start(student(), sessionId, StreamType.CAMERA);
        service.start(student(), sessionId, StreamType.SCREEN);

        List<RecordingStreamView> views = service.list(student(), sessionId);

        assertThat(views).extracting(RecordingStreamView::streamType)
                .containsExactly("camera", "screen");
        assertCode(() -> service.list(student2(), sessionId), ErrorCode.FORBIDDEN);
        assertCode(() -> service.list(admin(), sessionId), ErrorCode.FORBIDDEN);
    }

    private Recording recording(SessionId sessionId) {
        return recordings.findBySession(sessionId).orElseThrow();
    }

    private SessionId createSession(TaskId targetTaskId, TaskMode mode, SessionStatus status) {
        Session session = Session.createPreparing(
                new SessionId(UUID.randomUUID()), SessionType.TASK, targetTaskId,
                COURSE, STUDENT, mode, 1, NOW);
        switch (status) {
            case PREPARING -> {
            }
            case IN_PROGRESS -> session.start(NOW);
            case ENDED -> {
                session.start(NOW);
                session.end(NOW);
            }
            case SUBMITTED -> {
                session.start(NOW);
                session.end(NOW);
                session.submit(NOW);
            }
            case CANCELLED -> session.cancel(NOW);
            case FAILED -> {
                session.start(NOW);
                session.fail(FailureReason.RECORDING_LOST, NOW);
            }
            default -> throw new IllegalArgumentException("unsupported test status: " + status);
        }
        sessions.save(session);
        return session.id();
    }

    /** Seeds an existing UPLOADING stream and a matching in-memory multipart upload. */
    private String seedMultipartStream(SessionId sessionId, StreamType type) {
        String objectKey = "recordings/" + SCHOOL_ID + "/task/" + taskId.value() + "/"
                + sessionId.value() + "/" + type.wireValue() + ".webm";
        String uploadId = storage.createMultipart(objectKey, type == StreamType.AUDIO
                ? "audio/webm" : "video/webm").uploadId();
        Recording recording = recordings.findBySession(sessionId)
                .orElseGet(() -> Recording.create(UUID.randomUUID(), sessionId, NOW));
        recording.startStream(UUID.randomUUID(), TaskMode.FREE, type,
                uploadId, objectKey, PART_SIZE, 0L,
                type == StreamType.AUDIO ? "audio/webm" : "video/webm", NOW);
        recordings.save(recording);
        return objectKey;
    }

    private TaskId publishTask(TaskMode mode) {
        TaskDraft draft = mode == TaskMode.INTERVIEW
                ? new TaskDraft(TaskMode.INTERVIEW, "结构化面试", null, 300, 300,
                        RubricWeights.defaults(TaskMode.INTERVIEW), null, List.of(),
                        new QuestionConfig(List.of(), 300))
                : freeDraft();
        Task task = Task.createDraft(
                new TaskId(UUID.randomUUID()), COURSE, TEACHER, draft, NOW);
        TaskSnapshot snapshot = new TaskSnapshotFactory().create(
                task, course(), List.of(), NOW);
        task.publish(snapshot, NOW);
        tasks.save(task);
        return task.id();
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expected));
    }

    /** Fails multipart completion and counts best-effort abort attempts. */
    private static final class FailingCompleteStorage implements RecordingObjectStorage {
        private final RecordingObjectStorage delegate;
        private int abortCalls;

        private FailingCompleteStorage(RecordingObjectStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public MultipartUpload createMultipart(String objectKey, String contentType) {
            return delegate.createMultipart(objectKey, contentType);
        }

        @Override
        public String presignPart(
                String objectKey, String uploadId, int partNumber, Duration ttl) {
            return delegate.presignPart(objectKey, uploadId, partNumber, ttl);
        }

        @Override
        public void completeMultipart(
                String objectKey, String uploadId, List<PartEtag> parts) {
            throw new IllegalStateException("complete failed");
        }

        @Override
        public void abortMultipart(String objectKey, String uploadId) {
            abortCalls++;
        }

        @Override
        public String presignGet(String objectKey, Duration ttl) {
            return delegate.presignGet(objectKey, ttl);
        }

        @Override
        public ObjectStat stat(String objectKey) {
            return delegate.stat(objectKey);
        }

        @Override
        public void delete(String objectKey) {
            delegate.delete(objectKey);
        }

        private int abortCalls() {
            return abortCalls;
        }
    }

    /** Delegates every storage call but reports a fixed content type from {@code stat}. */
    private static final class MimeOverridingStorage implements RecordingObjectStorage {
        private final RecordingObjectStorage delegate;
        private final String contentType;

        private MimeOverridingStorage(
                RecordingObjectStorage delegate, String contentType) {
            this.delegate = delegate;
            this.contentType = contentType;
        }

        @Override
        public MultipartUpload createMultipart(String objectKey, String contentType) {
            return delegate.createMultipart(objectKey, contentType);
        }

        @Override
        public String presignPart(
                String objectKey, String uploadId, int partNumber, Duration ttl) {
            return delegate.presignPart(objectKey, uploadId, partNumber, ttl);
        }

        @Override
        public void completeMultipart(
                String objectKey, String uploadId, List<PartEtag> parts) {
            delegate.completeMultipart(objectKey, uploadId, parts);
        }

        @Override
        public void abortMultipart(String objectKey, String uploadId) {
            delegate.abortMultipart(objectKey, uploadId);
        }

        @Override
        public String presignGet(String objectKey, Duration ttl) {
            return delegate.presignGet(objectKey, ttl);
        }

        @Override
        public ObjectStat stat(String objectKey) {
            ObjectStat stat = delegate.stat(objectKey);
            return new ObjectStat(stat.size(), contentType);
        }

        @Override
        public void delete(String objectKey) {
            delegate.delete(objectKey);
        }
    }

    private static final class InMemoryRecordingRepository implements RecordingRepository {
        private final Map<SessionId, Recording> bySession = new LinkedHashMap<>();

        @Override
        public Recording save(Recording recording) {
            bySession.put(recording.sessionId(), recording);
            return recording;
        }

        @Override
        public Optional<Recording> findBySession(SessionId sessionId) {
            return Optional.ofNullable(bySession.get(sessionId));
        }

        @Override
        public Optional<Recording> findById(UUID id) {
            return bySession.values().stream()
                    .filter(recording -> recording.id().equals(id))
                    .findFirst();
        }

        @Override
        public void deleteBySession(SessionId sessionId) {
            bySession.remove(sessionId);
        }
    }

    /**
     * Stores rehydrated copies (like the JPA adapter) and fails the first save that carries an
     * uploaded stream, simulating a commit-time persistence failure.
     */
    private static final class FailingUploadedSaveRecordingRepository
            implements RecordingRepository {
        private final Map<SessionId, Recording> bySession = new LinkedHashMap<>();
        private boolean failedOnce;

        @Override
        public Recording save(Recording recording) {
            boolean hasUploadedStream = recording.streams().stream()
                    .anyMatch(stream -> stream.status() == StreamStatus.UPLOADED);
            if (hasUploadedStream && !failedOnce) {
                failedOnce = true;
                throw new IllegalStateException("recording row unavailable");
            }
            bySession.put(recording.sessionId(), copy(recording));
            return recording;
        }

        @Override
        public Optional<Recording> findBySession(SessionId sessionId) {
            return Optional.ofNullable(bySession.get(sessionId))
                    .map(FailingUploadedSaveRecordingRepository::copy);
        }

        @Override
        public Optional<Recording> findById(UUID id) {
            return bySession.values().stream()
                    .filter(recording -> recording.id().equals(id))
                    .findFirst()
                    .map(FailingUploadedSaveRecordingRepository::copy);
        }

        @Override
        public void deleteBySession(SessionId sessionId) {
            bySession.remove(sessionId);
        }

        private static Recording copy(Recording recording) {
            return Recording.rehydrate(
                    recording.id(),
                    recording.sessionId(),
                    recording.status(),
                    recording.streams(),
                    recording.createdAt(),
                    recording.updatedAt());
        }
    }
}
