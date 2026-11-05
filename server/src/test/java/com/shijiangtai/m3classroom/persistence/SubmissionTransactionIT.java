package com.shijiangtai.m3classroom.persistence;

import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.RecordingApplicationService;
import com.shijiangtai.m3classroom.application.RecordingLifecycleService;
import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.application.SessionApplicationService;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.command.CompletePartCommand;
import com.shijiangtai.m3classroom.application.port.RecordingLifecyclePort;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.PartEtag;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.view.MultipartUploadView;
import com.shijiangtai.m3classroom.application.view.SubmissionView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.m3classroom.infrastructure.persistence.jpa.SessionEventStoreAdapter;
import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryRecordingObjectStorage;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transaction boundaries of the submission flow.
 *
 * <p>The cleanup probe records whether {@code deleteOtherAttempts} entered while a transaction
 * synchronization was still active. The old after-commit registration entered from the submit
 * transaction's {@code afterCommit} callback (active), where the deletes joined a completed
 * transaction and never committed. The facade now calls cleanup only after {@code submit} has
 * committed, so the entry must observe no active synchronization and the deletes must be durable.
 *
 * <p>The event-store wrapper fails the {@code submission.created} append to prove that the whole
 * submit transaction (submission, session, assignment, event) rolls back and cleanup is skipped.
 */
@Import(SubmissionTransactionIT.TransactionProbeConfig.class)
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=2")
class SubmissionTransactionIT extends PostgresIntegrationTest {

    private static final long RECORDING_SIZE = 1_024L;

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskMaterialService materialService;

    @Autowired
    private TaskPublishService publishService;

    @Autowired
    private SessionApplicationService sessionApplicationService;

    @Autowired
    private RecordingApplicationService recordingApplicationService;

    @Autowired
    private SessionRuntimeRegistry runtimeRegistry;

    @Autowired
    private InMemoryRecordingObjectStorage storage;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private TaskAssignmentRepository assignmentRepository;

    @Autowired
    private SessionEventStore eventStore;

    @Autowired
    private CleanupProbe cleanupProbe;

    @Autowired
    private FailureControl failureControl;

    private TaskId taskId;

    @BeforeEach
    void publishTask() {
        cleanupProbe.reset();
        failureControl.failSubmissionCreated(false);
        TaskView view = taskApplicationService.createDraft(teacher(), freeCommandWithStudent());
        taskId = new TaskId(view.taskId());
        materialService.upload(
                teacher(), taskId, "a.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于测试发布。"
                        .getBytes(StandardCharsets.UTF_8));
        publishService.publish(teacher(), taskId);
    }

    @Test
    void submitRemovesSupersededAttemptRowsAndObjectsKeepingTheSubmittedOne() {
        SessionId previous = createSession();
        List<String> previousKeys = uploadAll(previous);
        sessionApplicationService.cancel(student(), previous);
        assertThat(sessionRepository.findById(previous).orElseThrow().status())
                .isEqualTo(SessionStatus.CANCELLED);
        assertThat(recordingRepository.findBySession(previous)).isPresent();

        SessionId keep = createSession();
        startSession(keep);
        List<String> keepKeys = uploadAll(keep);
        sessionApplicationService.end(student(), keep);

        SubmissionView view = sessionApplicationService.submit(student(), keep);

        assertThat(submissionRepository.findBySession(keep)).isPresent();
        assertThat(view.taskId()).isEqualTo(taskId.value());
        assertThat(sessionRepository.findById(previous)).isEmpty();
        assertThat(recordingRepository.findBySession(previous)).isEmpty();
        assertThat(sessionRepository.findById(keep).orElseThrow().status())
                .isEqualTo(SessionStatus.SUBMITTED);
        assertThat(recordingRepository.findBySession(keep)).isPresent();
        for (String key : previousKeys) {
            assertThatThrownBy(() -> storage.stat(key)).isInstanceOf(DomainException.class);
        }
        for (String key : keepKeys) {
            assertThat(storage.stat(key)).isNotNull();
        }
        assertThat(cleanupProbe.synchronizationActiveAtCleanup()).containsExactly(false);
    }

    @Test
    void submissionCreatedAppendFailureRollsBackSubmissionAndSkipsCleanup() {
        SessionId previous = createSession();
        sessionApplicationService.cancel(student(), previous);

        SessionId keep = createSession();
        startSession(keep);
        uploadAll(keep);
        sessionApplicationService.end(student(), keep);

        failureControl.failSubmissionCreated(true);

        assertThatThrownBy(() -> sessionApplicationService.submit(student(), keep))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("controlled submission.created append failure");

        assertThat(submissionRepository.findBySession(keep)).isEmpty();
        assertThat(sessionRepository.findById(keep).orElseThrow().status())
                .isEqualTo(SessionStatus.ENDED);
        assertThat(assignmentRepository.find(taskId, STUDENT).orElseThrow().status())
                .isEqualTo(AssignmentStatus.ASSIGNED);
        assertThat(eventStore.after(keep, 0, 500))
                .noneMatch(event -> event.type().equals("submission.created"));
        assertThat(sessionRepository.findById(previous)).isPresent();
        assertThat(cleanupProbe.synchronizationActiveAtCleanup()).isEmpty();
    }

    private SessionId createSession() {
        return new SessionId(
                sessionApplicationService.createSession(student(), taskId).sessionId());
    }

    private void startSession(SessionId sessionId) {
        SessionActor actor = runtimeRegistry.getOrCreate(sessionId);
        List<SessionCommand.RecordingReady.StreamStatus> streams = new ArrayList<>();
        for (StreamType type : StreamType.values()) {
            streams.add(
                    new SessionCommand.RecordingReady.StreamStatus(type, "ready"));
        }
        assertThat(actor.handle(
                new SessionCommand.RecordingReady("ready-" + sessionId.value(), streams)).ok())
                .isTrue();
        assertThat(sessionApplicationService.start(student(), sessionId).status())
                .isEqualTo("in_progress");
    }

    private List<String> uploadAll(SessionId sessionId) {
        List<String> keys = new ArrayList<>();
        for (StreamType type : StreamType.values()) {
            MultipartUploadView upload =
                    recordingApplicationService.start(student(), sessionId, type);
            storage.recordObjectSize(upload.objectKey(), RECORDING_SIZE);
            String mime = type == StreamType.AUDIO ? "audio/webm" : "video/webm";
            recordingApplicationService.complete(student(), sessionId, type,
                    new CompletePartCommand(
                            List.of(new PartEtag(1, "etag-1")), 5_000L,
                            RECORDING_SIZE, mime));
            keys.add(upload.objectKey());
        }
        return keys;
    }

    static final class CleanupProbe {
        private final List<Boolean> synchronizationActive = new CopyOnWriteArrayList<>();

        void record(boolean active) {
            synchronizationActive.add(active);
        }

        void reset() {
            synchronizationActive.clear();
        }

        List<Boolean> synchronizationActiveAtCleanup() {
            return List.copyOf(synchronizationActive);
        }
    }

    static final class FailureControl {
        private volatile boolean failSubmissionCreated;

        void failSubmissionCreated(boolean value) {
            this.failSubmissionCreated = value;
        }

        boolean failSubmissionCreated() {
            return failSubmissionCreated;
        }
    }

    @TestConfiguration
    static class TransactionProbeConfig {

        @Bean
        CleanupProbe cleanupProbe() {
            return new CleanupProbe();
        }

        @Bean
        FailureControl failureControl() {
            return new FailureControl();
        }

        @Bean
        @Primary
        RecordingLifecyclePort cleanupProbeLifecyclePort(
                RecordingLifecycleService delegate, CleanupProbe probe) {
            return new RecordingLifecyclePort() {
                @Override
                public void abortAll(SessionId sessionId) {
                    delegate.abortAll(sessionId);
                }

                @Override
                public void deleteAttempt(SessionId sessionId) {
                    delegate.deleteAttempt(sessionId);
                }

                @Override
                public void deleteOtherAttempts(
                        AccountId student, TaskId task, SessionId keep) {
                    probe.record(TransactionSynchronizationManager.isSynchronizationActive());
                    delegate.deleteOtherAttempts(student, task, keep);
                }
            };
        }

        @Bean
        @Primary
        SessionEventStore failingSessionEventStore(
                SessionEventStoreAdapter delegate, FailureControl control) {
            return new SessionEventStore() {
                @Override
                public void append(SessionEvent event) {
                    if (control.failSubmissionCreated()
                            && event.type().equals("submission.created")) {
                        throw new IllegalStateException(
                                "controlled submission.created append failure");
                    }
                    delegate.append(event);
                }

                @Override
                public void appendAll(List<SessionEvent> events) {
                    events.forEach(this::append);
                }

                @Override
                public List<SessionEvent> after(SessionId id, long seq, int limit) {
                    return delegate.after(id, seq, limit);
                }

                @Override
                public long maxSeq(SessionId id) {
                    return delegate.maxSeq(id);
                }
            };
        }
    }
}
