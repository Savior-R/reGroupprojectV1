package com.shijiangtai.m3classroom.persistence;

import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.RecordingApplicationService;
import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.application.SessionApplicationService;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.command.CompletePartCommand;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.MultipartUpload;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.ObjectStat;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.PartEtag;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.view.MultipartUploadView;
import com.shijiangtai.m3classroom.application.view.SessionView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryRecordingObjectStorage;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Real PostgreSQL concurrency regressions for recording multipart mutations and cleanup.
 *
 * <p>Without the per-session row lock the three requests race on the recording aggregate: two
 * transactions can both create a recording row (violating {@code uq_recordings_session}) or each
 * save an aggregate that does not contain the sibling streams. The lock makes start/complete/abort
 * serialize per session; every start must therefore see the streams committed by its predecessor.
 *
 * <p>The deadlock test also covers the lock/monitor ordering between a recording mutation and the
 * live actor: the mutation transaction holds the session row lock, and a concurrent end/timeout
 * holds the actor monitor while its status listener saves the terminal row. Failing the session
 * from inside the locked transaction would deadlock (Java monitor vs. row lock, undetected by
 * PostgreSQL); the mutation transaction must end first.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecordingConcurrencyIT extends PostgresIntegrationTest {

    private static final int THREADS = 3;
    private static final long ASYNC_TIMEOUT_SECONDS = 20L;

    @TestConfiguration
    static class ControllableStorageConfig {

        @Bean
        @Primary
        ControllableRecordingObjectStorage controllableRecordingObjectStorage(
                InMemoryRecordingObjectStorage delegate) {
            return new ControllableRecordingObjectStorage(delegate);
        }
    }

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskMaterialService materialService;

    @Autowired
    private TaskPublishService publishService;

    @Autowired
    private SessionApplicationService sessionApplicationService;

    @Autowired
    private SessionRuntimeRegistry runtimeRegistry;

    @Autowired
    private RecordingApplicationService recordingApplicationService;

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionEventStore eventStore;

    @Autowired
    private ControllableRecordingObjectStorage storage;

    @Autowired
    private JdbcTemplate jdbc;

    private SessionId currentSessionId;

    @AfterEach
    void evictActor() {
        if (currentSessionId != null) {
            runtimeRegistry.remove(currentSessionId);
            currentSessionId = null;
        }
    }

    @Test
    void threeSimultaneousStartsKeepOneRecordingWithThreeStreams() throws Exception {
        SessionId sessionId = createSession(publishFreeTask());

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MultipartUploadView>> starts = new ArrayList<>();
            for (StreamType type : List.of(
                    StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO)) {
                starts.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent start barrier timed out");
                    }
                    return recordingApplicationService.start(student(), sessionId, type);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            for (Future<MultipartUploadView> start : starts) {
                MultipartUploadView upload = start.get(30, TimeUnit.SECONDS);
                assertThat(upload.uploadId()).isNotBlank();
                assertThat(upload.objectKey()).isNotBlank();
            }
        } finally {
            pool.shutdownNow();
        }

        Recording recording = recordingRepository.findBySession(sessionId).orElseThrow();
        assertThat(recording.streams())
                .extracting(RecordingStream::type)
                .containsExactlyInAnyOrder(
                        StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO);
        assertThat(recording.streams()).hasSize(3);
        Integer recordings = jdbc.queryForObject(
                "SELECT count(*) FROM m3_classroom.recordings WHERE session_id = ?",
                Integer.class, sessionId.value());
        Integer streams = jdbc.queryForObject("""
                SELECT count(*) FROM m3_classroom.recording_streams
                WHERE recording_id = ?
                """, Integer.class, recording.id());
        assertThat(recordings).isEqualTo(1);
        assertThat(streams).isEqualTo(3);
    }

    /**
     * Deterministic deadlock regression. Abort is held inside the locked mutation transaction
     * (blocked in {@code abortMultipart}) after it acquired the session row lock. The end thread
     * then enters the actor, holds its monitor and blocks saving the terminal row on that same
     * row lock. Releasing abort lets it reach the session-failure transition. In the broken
     * implementation abort would wait on the actor monitor while still holding the row lock, and
     * end would wait on the row lock while holding the monitor: a hard deadlock that neither
     * transaction would resolve.
     */
    @Test
    void abortAndConcurrentActorEndDoNotDeadlock() throws Exception {
        SessionId sessionId = createStartedSession();
        recordingApplicationService.start(student(), sessionId, StreamType.CAMERA);

        storage.blockNextAbort();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> abort = pool.submit(() -> {
                recordingApplicationService.abort(student(), sessionId, StreamType.CAMERA);
                return null;
            });
            assertThat(storage.awaitAbortReached(10, TimeUnit.SECONDS)).isTrue();

            Future<Void> end = pool.submit(() -> {
                try {
                    sessionApplicationService.end(student(), sessionId);
                } catch (DomainException expectedRace) {
                    // End may lose the race: the abort already failed the session.
                    assertThat(expectedRace.code()).isIn(
                            ErrorCode.SESSION_STATE_INVALID, ErrorCode.SESSION_FAILED);
                }
                return null;
            });
            // Let end take the actor monitor and block on the session row lock held by abort.
            Thread.sleep(500);
            storage.releaseAbort();

            abort.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            end.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            storage.releaseAbort();
        }

        assertThat(sessionRepository.findById(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
        assertThat(recordingRepository.findBySession(sessionId).orElseThrow()
                .stream(StreamType.CAMERA).orElseThrow().status())
                .isEqualTo(StreamStatus.FAILED);
    }

    /**
     * A non-domain failure (storage/repository infrastructure) rolls the recording transaction
     * back by default. The session-failure write runs in its own {@code REQUIRES_NEW} transaction
     * afterwards, so the persisted {@code failed} row and {@code session.failed} event survive.
     *
     * <p>The failed-stream write also survives because {@code RecordingRepositoryAdapter.save}
     * deliberately uses {@code REQUIRES_NEW} (a failed save must not mark the caller rollback-only);
     * therefore the assertion that matters here is the session row plus exactly one terminal
     * event, not the stream row of the rolled-back attempt.
     */
    @Test
    void sessionFailureSurvivesRecordingTransactionRollback() {
        SessionId sessionId = createSession(publishFreeTask());
        recordingApplicationService.start(student(), sessionId, StreamType.CAMERA);
        storage.failNextComplete();

        assertThatThrownBy(() -> recordingApplicationService.complete(
                        student(), sessionId, StreamType.CAMERA,
                        new CompletePartCommand(
                                List.of(new PartEtag(1, "etag-1")), 5_000, 1024,
                                "video/webm")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("controlled complete failure");

        assertThat(sessionRepository.findById(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
        List<SessionEvent> failedEvents = eventStore.after(sessionId, 0, 100).stream()
                .filter(event -> "session.failed".equals(event.type()))
                .toList();
        assertThat(failedEvents).hasSize(1);
    }

    private TaskId publishFreeTask() {
        TaskView view = taskApplicationService.createDraft(
                teacher(), freeCommandWithStudent());
        TaskId taskId = new TaskId(view.taskId());
        materialService.upload(
                teacher(), taskId, "a.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于测试发布。"
                        .getBytes(StandardCharsets.UTF_8));
        publishService.publish(teacher(), taskId);
        return taskId;
    }

    private SessionId createSession(TaskId taskId) {
        SessionView created = sessionApplicationService.createSession(student(), taskId);
        currentSessionId = new SessionId(created.sessionId());
        return currentSessionId;
    }

    private SessionId createStartedSession() {
        SessionId sessionId = createSession(publishFreeTask());
        SessionActor actor = runtimeRegistry.getOrCreate(sessionId);
        List<SessionCommand.RecordingReady.StreamStatus> streams = List.of(
                new SessionCommand.RecordingReady.StreamStatus(StreamType.CAMERA, "ready"),
                new SessionCommand.RecordingReady.StreamStatus(StreamType.SCREEN, "ready"),
                new SessionCommand.RecordingReady.StreamStatus(StreamType.AUDIO, "ready"));
        assertThat(actor.handle(
                new SessionCommand.RecordingReady("ready-" + UUID.randomUUID(), streams)).ok())
                .isTrue();
        sessionApplicationService.start(student(), sessionId);
        return sessionId;
    }

    /**
     * Delegating storage with two test switches: block the next multipart abort after its caller
     * has taken the session row lock, and fail the next multipart completion.
     */
    static final class ControllableRecordingObjectStorage implements RecordingObjectStorage {

        private final RecordingObjectStorage delegate;
        private final CountDownLatch abortReached = new CountDownLatch(1);
        private final CountDownLatch releaseAbort = new CountDownLatch(1);
        private volatile boolean blockAbort;
        private volatile boolean failNextComplete;

        ControllableRecordingObjectStorage(RecordingObjectStorage delegate) {
            this.delegate = delegate;
        }

        void blockNextAbort() {
            blockAbort = true;
        }

        boolean awaitAbortReached(long timeout, TimeUnit unit) throws InterruptedException {
            return abortReached.await(timeout, unit);
        }

        void releaseAbort() {
            blockAbort = false;
            releaseAbort.countDown();
        }

        void failNextComplete() {
            failNextComplete = true;
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
            if (failNextComplete) {
                failNextComplete = false;
                throw new IllegalStateException("controlled complete failure");
            }
            delegate.completeMultipart(objectKey, uploadId, parts);
        }

        @Override
        public void abortMultipart(String objectKey, String uploadId) {
            if (blockAbort) {
                abortReached.countDown();
                try {
                    if (!releaseAbort.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("abort block release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("abort block interrupted", exception);
                }
            }
            delegate.abortMultipart(objectKey, uploadId);
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
    }
}