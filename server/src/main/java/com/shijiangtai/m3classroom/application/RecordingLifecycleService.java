package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.AfterCommitPort;
import com.shijiangtai.m3classroom.application.port.RecordingLifecyclePort;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording side effects of session lifecycle operations (05B §6).
 *
 * <p>Replaces the no-op {@code RecordingLifecyclePort} bean from {@code M3Config}: because this
 * class is a component, the conditional fallback is suppressed. Object-store deletes are
 * scheduled after commit, while the recording/session rows are removed in the current
 * transaction and the database cascades {@code recording_streams}.
 */
@Service
public class RecordingLifecycleService implements RecordingLifecyclePort {

    private static final Logger log = LoggerFactory.getLogger(RecordingLifecycleService.class);

    private final RecordingRepository recordingRepository;
    private final SessionRepository sessionRepository;
    private final RecordingObjectStorage storage;
    private final AfterCommitPort afterCommitPort;
    private final ClockProvider clockProvider;

    public RecordingLifecycleService(
            RecordingRepository recordingRepository,
            SessionRepository sessionRepository,
            RecordingObjectStorage storage,
            AfterCommitPort afterCommitPort,
            ClockProvider clockProvider) {
        this.recordingRepository = Objects.requireNonNull(
                recordingRepository, "recordingRepository");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.afterCommitPort = Objects.requireNonNull(afterCommitPort, "afterCommitPort");
        this.clockProvider = Objects.requireNonNull(clockProvider, "clockProvider");
    }

    @Override
    @Transactional
    public void abortAll(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Recording recording = recordingRepository.findBySession(sessionId).orElse(null);
        if (recording == null) {
            return;
        }
        boolean changed = false;
        for (RecordingStream stream : recording.streams()) {
            if (stream.status() != StreamStatus.UPLOADING || stream.uploadId() == null) {
                continue;
            }
            try {
                storage.abortMultipart(stream.objectKey(), stream.uploadId());
            } catch (RuntimeException exception) {
                log.warn("Aborting recording multipart failed: session={}, stream={}",
                        sessionId.value(), stream.type().wireValue(), exception);
            }
            recording.abortStream(stream.type(), clockProvider.now());
            changed = true;
        }
        if (changed) {
            recordingRepository.save(recording);
        }
    }

    @Override
    @Transactional
    public void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(keep, "keep");
        List<Runnable> objectDeletes = new ArrayList<>();
        for (Session session : sessionRepository.findByStudentAndTask(student, task)) {
            if (session.id().equals(keep)) {
                continue;
            }
            Recording recording = recordingRepository.findBySession(session.id()).orElse(null);
            if (recording != null) {
                for (RecordingStream stream : recording.streams()) {
                    if (stream.objectKey() != null) {
                        String objectKey = stream.objectKey();
                        objectDeletes.add(() -> deleteObject(objectKey));
                    }
                }
                recordingRepository.deleteBySession(session.id());
            }
            sessionRepository.deleteById(session.id());
        }
        if (!objectDeletes.isEmpty()) {
            afterCommitPort.afterCommit(() -> objectDeletes.forEach(Runnable::run));
        }
    }

    private void deleteObject(String objectKey) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.warn("Deleting recording object failed: objectKey={}", objectKey, exception);
        }
    }
}
