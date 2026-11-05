package com.shijiangtai.m3classroom.persistence;

import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.application.SessionApplicationService;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.ContextPersistenceWriter;
import com.shijiangtai.m3classroom.application.port.PersistenceFlushException;
import com.shijiangtai.m3classroom.application.port.SchedulerPort;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * End-to-end acceptance for the REST end path when the terminal critical flush fails.
 *
 * <p>The production {@link SessionRuntimeRegistry} is replaced by a primary registry wired to a
 * controllable in-memory {@link ContextPersistenceWriter}. The registry's status listener still
 * saves the derived {@code failed} row inside the caller's transaction; {@link
 * SessionApplicationService#end} uses {@code @Transactional(noRollbackFor = DomainException.class)}
 * so that row commits even though {@code SESSION_FAILED} propagates. This test would fail if the
 * rollback override were removed (the row stays {@code in_progress}) or if the registry stopped
 * saving the terminal row before evicting the actor.
 */
class SessionEndPersistenceFailureIT extends PostgresIntegrationTest {

    @TestConfiguration
    static class ControllablePersistenceConfig {

        @Bean
        @Primary
        ControllableContextPersistenceWriter controllableContextPersistenceWriter() {
            return new ControllableContextPersistenceWriter();
        }

        @Bean
        @Primary
        SessionRuntimeRegistry controllableSessionRuntimeRegistry(
                SessionRepository sessionRepository,
                TaskRepository taskRepository,
                SessionEventStore eventStore,
                SessionTextStore textStore,
                IdGenerator idGenerator,
                ClockProvider clockProvider,
                SchedulerPort scheduler,
                ContentClient contentClient,
                ControllableContextPersistenceWriter writer) {
            return new SessionRuntimeRegistry(
                    sessionRepository, taskRepository, eventStore, textStore, idGenerator,
                    clockProvider, scheduler, contentClient, writer, Random::new);
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
    private SessionRepository sessionRepository;

    @Autowired
    private SessionEventStore eventStore;

    @Autowired
    private ControllableContextPersistenceWriter writer;

    private SessionId currentSessionId;

    @AfterEach
    void stopActor() {
        if (currentSessionId != null) {
            runtimeRegistry.remove(currentSessionId);
            currentSessionId = null;
        }
    }

    @Test
    void restEndCommitsFailedRowAndEvictsActorWhenTerminalFlushFails() {
        SessionId sessionId = createAndStartSession();
        SessionActor liveActor = runtimeRegistry.find(sessionId).orElseThrow();
        assertThat(liveActor.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
        writer.failFlush();

        assertThatThrownBy(() -> sessionApplicationService.end(student(), sessionId))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SESSION_FAILED));

        // The registry-derived terminal row committed despite the domain exception.
        assertThat(sessionRepository.findById(sessionId))
                .hasValueSatisfying(session -> assertThat(session.status())
                        .isEqualTo(SessionStatus.FAILED));
        // The terminal status listener removed the failed actor from the live map.
        assertThat(runtimeRegistry.find(sessionId)).isEmpty();
        // The actor attempted the critical terminal flush (and the best-effort retry) before failing.
        assertThat(writer.flushCalls()).isGreaterThanOrEqualTo(1);
        assertThat(writer.failedFlushAttempts()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void restViewReportsLiveLastSeqWhileAsyncWritesAreBuffered() {
        SessionId sessionId = createAndStartSession();
        SessionActor actor = runtimeRegistry.find(sessionId).orElseThrow();
        long liveSeq = actor.lastSeq();
        assertThat(liveSeq).isPositive();

        var view = sessionApplicationService.get(student(), sessionId);

        // The buffered writer has persisted nothing, yet REST must not report a rewound sequence.
        assertThat(eventStore.maxSeq(sessionId)).isZero();
        assertThat(view.lastSeq()).isGreaterThanOrEqualTo(liveSeq);
    }

    private SessionId createAndStartSession() {
        TaskId taskId = publishFreeTask();
        SessionId sessionId = new SessionId(sessionApplicationService
                .createSession(student(), taskId).sessionId());
        currentSessionId = sessionId;
        start(sessionId);
        return sessionId;
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

    private void start(SessionId id) {
        SessionActor actor = runtimeRegistry.getOrCreate(id);
        List<SessionCommand.RecordingReady.StreamStatus> streams = List.of(
                new SessionCommand.RecordingReady.StreamStatus(StreamType.CAMERA, "ready"),
                new SessionCommand.RecordingReady.StreamStatus(StreamType.SCREEN, "ready"),
                new SessionCommand.RecordingReady.StreamStatus(StreamType.AUDIO, "ready"));
        assertThat(actor.handle(
                new SessionCommand.RecordingReady("ready-" + UUID.randomUUID(), streams)).ok())
                .isTrue();
        sessionApplicationService.start(student(), id);
    }

    /**
     * In-memory writer with a switchable failure mode: appends buffer in call order, a successful
     * flush drops them, a failing flush throws without draining, and {@code close} is a no-op so
     * the registry's pre-save close cannot swallow the terminal failure or clear the counters.
     */
    static final class ControllableContextPersistenceWriter implements ContextPersistenceWriter {

        private final List<SessionEvent> pendingEvents = new ArrayList<>();
        private final List<SessionText> pendingTexts = new ArrayList<>();
        private boolean failFlush;
        private int flushCalls;
        private int failedFlushAttempts;

        @Override
        public synchronized void appendEvent(SessionEvent event) {
            pendingEvents.add(event);
        }

        @Override
        public synchronized void saveText(SessionText text) {
            pendingTexts.add(text);
        }

        @Override
        public synchronized void flush(SessionId sessionId) {
            flushCalls++;
            if (failFlush) {
                failedFlushAttempts++;
                throw new PersistenceFlushException(sessionId, "controlled terminal flush failure");
            }
            pendingEvents.clear();
            pendingTexts.clear();
        }

        /** Deliberately no flush: the registry calls this before saving the terminal row. */
        @Override
        public void close(SessionId sessionId) {
            // no-op
        }

        synchronized void failFlush() {
            failFlush = true;
        }

        synchronized int flushCalls() {
            return flushCalls;
        }

        synchronized int failedFlushAttempts() {
            return failedFlushAttempts;
        }
    }
}
