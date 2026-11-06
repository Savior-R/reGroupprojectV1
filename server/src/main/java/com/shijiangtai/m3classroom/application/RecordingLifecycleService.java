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
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Duration;
import java.time.Instant;
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
 * <p>This is the only {@link RecordingLifecyclePort} implementation. Object-store deletes are
 * scheduled after commit, while the recording/session rows are removed in the current
 * transaction and the database cascades {@code recording_streams}.
 */
@Service
public class RecordingLifecycleService implements RecordingLifecyclePort {

    private static final Logger log = LoggerFactory.getLogger(RecordingLifecycleService.class);
    private static final Duration TEMPORARY_RETENTION = Duration.ofHours(24);

    private final RecordingRepository recordingRepository;
    private final SessionRepository sessionRepository;
    private final TaskRepository taskRepository;
    private final SubmissionRepository submissionRepository;
    private final RecordingObjectStorage storage;
    private final AfterCommitPort afterCommitPort;
    private final ClockProvider clockProvider;

    public RecordingLifecycleService(
            RecordingRepository recordingRepository,
            SessionRepository sessionRepository,
            TaskRepository taskRepository,
            SubmissionRepository submissionRepository,
            RecordingObjectStorage storage,
            AfterCommitPort afterCommitPort,
            ClockProvider clockProvider) {
        this.recordingRepository = Objects.requireNonNull(
                recordingRepository, "recordingRepository");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.submissionRepository = Objects.requireNonNull(
                submissionRepository, "submissionRepository");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.afterCommitPort = Objects.requireNonNull(afterCommitPort, "afterCommitPort");
        this.clockProvider = Objects.requireNonNull(clockProvider, "clockProvider");
    }

    /**
     * Session cancellation keeps the session row but invalidates its temporary recording: every
     * in-flight multipart upload is aborted, all streams and the recording become {@code deleted},
     * and the corresponding objects are removed after the transaction commits.
     */
    @Override
    @Transactional
    public void abortAll(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Recording recording = recordingRepository.findBySession(sessionId).orElse(null);
        if (recording == null) {
            return;
        }
        List<Runnable> objectDeletes = new ArrayList<>();
        for (RecordingStream stream : recording.streams()) {
            abortStreamUpload(stream);
            scheduleObjectDelete(stream, objectDeletes);
        }
        recording.markDeleted(clockProvider.now());
        recordingRepository.save(recording);
        scheduleAfterCommit(objectDeletes);
    }

    /**
     * Removes one superseded attempt. Only closed, non-submitted attempts are deletable; a
     * submitted attempt is referenced by its submission/replay and an active attempt is still
     * being recorded.
     */
    @Override
    @Transactional
    public void deleteAttempt(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        List<Runnable> objectDeletes = removeAttemptRows(sessionId);
        scheduleAfterCommit(objectDeletes);
    }

    @Override
    @Transactional
    public void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(keep, "keep");
        List<Runnable> objectDeletes = new ArrayList<>();
        for (Session session : sessionRepository.findByStudentAndTask(student, task)) {
            if (session.id().equals(keep) || !isDeletable(session.status())) {
                continue;
            }
            objectDeletes.addAll(removeAttemptRows(session.id()));
        }
        scheduleAfterCommit(objectDeletes);
    }

    /**
     * Deletes a temporary task session whose 24-hour retention expired. The expiry is recomputed
     * under the session lock because the task may have closed after the candidate query ran:
     * the anchor is {@code max(endedAt != null ? endedAt : updatedAt, task.closedAt)}.
     *
     * <p>Objects are deleted synchronously before any row. A {@link RuntimeException} from the
     * object store is not caught, so the transaction rolls back and the next cleanup run retries
     * the idempotent object deletes.
     */
    @Override
    @Transactional
    public boolean deleteExpiredTemporary(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Session locked = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (locked == null || !isDeletable(locked.status())) {
            return false;
        }
        if (!isTemporaryRetentionExpired(locked)) {
            return false;
        }
        Recording recording = recordingRepository.findBySession(sessionId).orElse(null);
        deleteObjectsNow(recording);
        if (recording != null) {
            recordingRepository.deleteBySession(sessionId);
        }
        sessionRepository.deleteById(sessionId);
        return true;
    }

    /**
     * Deletes a submitted session whose course retention expired. Objects are deleted before the
     * submission row, which must go before the session row to release the
     * {@code ON DELETE RESTRICT} submission-to-session foreign key.
     *
     * <p>Object-delete failures propagate unchanged so the transaction rolls back and the rows
     * stay available for the next cleanup run.
     */
    @Override
    @Transactional
    public boolean deleteSubmittedSession(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Session locked = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (locked == null || locked.status() != SessionStatus.SUBMITTED) {
            return false;
        }
        Recording recording = recordingRepository.findBySession(sessionId).orElse(null);
        deleteObjectsNow(recording);
        submissionRepository.deleteBySession(sessionId);
        if (recording != null) {
            recordingRepository.deleteBySession(sessionId);
        }
        sessionRepository.deleteById(sessionId);
        return true;
    }

    /**
     * Deletes every recording object synchronously. Deliberately does not catch failures: the
     * caller's transaction must roll back before any row is removed.
     */
    private void deleteObjectsNow(Recording recording) {
        if (recording == null) {
            return;
        }
        for (RecordingStream stream : recording.streams()) {
            if (stream.objectKey() != null) {
                storage.delete(stream.objectKey());
            }
        }
    }

    /**
     * Recomputes temporary retention under the lock: {@code anchor + 24h <= now} deletes, with
     * the anchor extended to the task close time when the task closed later.
     */
    private boolean isTemporaryRetentionExpired(Session session) {
        Instant anchor = session.endedAt() != null ? session.endedAt() : session.updatedAt();
        if (session.taskId() != null) {
            Instant closedAt = taskRepository.findById(session.taskId())
                    .map(Task::closedAt)
                    .orElse(null);
            if (closedAt != null && closedAt.isAfter(anchor)) {
                anchor = closedAt;
            }
        }
        return !anchor.plus(TEMPORARY_RETENTION).isAfter(clockProvider.now());
    }

    /**
     * Deletes recording/stream rows (streams cascade) and the session row itself.
     *
     * <p>The session row is loaded with the same {@code FOR NO KEY UPDATE} lock used by
     * {@code start/complete/abort}. Taking that lock first gives cleanup and a late completion the
     * same lock order (session row before recording rows), so they cannot deadlock. The
     * deletability check is repeated under the lock because a session may have changed status
     * since {@link #deleteOtherAttempts} listed the candidates.
     */
    private List<Runnable> removeAttemptRows(SessionId sessionId) {
        Session locked = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (locked == null || !isDeletable(locked.status())) {
            return List.of();
        }
        List<Runnable> objectDeletes = new ArrayList<>();
        Recording recording = recordingRepository.findBySession(sessionId).orElse(null);
        if (recording != null) {
            for (RecordingStream stream : recording.streams()) {
                abortStreamUpload(stream);
                scheduleObjectDelete(stream, objectDeletes);
            }
            recordingRepository.deleteBySession(sessionId);
        }
        sessionRepository.deleteById(sessionId);
        return objectDeletes;
    }

    /**
     * Aborts only streams whose multipart may still be open: {@code uploading} (never completed)
     * or {@code failed} (multipart consumed by a failed complete attempt). An {@code uploaded}
     * stream has no multipart left; aborting it always fails and only logs false warnings. Objects
     * of uploaded streams are still scheduled for deletion by {@link #scheduleObjectDelete}.
     * Aborts are best effort and idempotent.
     */
    private void abortStreamUpload(RecordingStream stream) {
        if (stream.uploadId() != null
                && (stream.status() == StreamStatus.UPLOADING
                        || stream.status() == StreamStatus.FAILED)) {
            abortUpload(stream);
        }
    }

    private void scheduleObjectDelete(
            RecordingStream stream, List<Runnable> objectDeletes) {
        if (stream.objectKey() == null) {
            return;
        }
        String objectKey = stream.objectKey();
        objectDeletes.add(() -> deleteObject(objectKey));
    }

    private void scheduleAfterCommit(List<Runnable> objectDeletes) {
        if (!objectDeletes.isEmpty()) {
            afterCommitPort.afterCommit(() -> objectDeletes.forEach(Runnable::run));
        }
    }

    /**
     * Only closed, non-submitted attempts may be removed. A submitted attempt is referenced by a
     * submission/replay, and an active attempt is still being recorded.
     */
    private static boolean isDeletable(SessionStatus status) {
        return status == SessionStatus.CANCELLED
                || status == SessionStatus.FAILED
                || status == SessionStatus.ENDED
                || status == SessionStatus.REVIEWING;
    }

    private void abortUpload(RecordingStream stream) {
        try {
            storage.abortMultipart(stream.objectKey(), stream.uploadId());
        } catch (RuntimeException exception) {
            log.warn("Aborting recording multipart failed: objectKey={}",
                    stream.objectKey(), exception);
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
