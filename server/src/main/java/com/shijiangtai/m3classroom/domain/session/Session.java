package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Session aggregate for a task attempt.
 *
 * <p>Lifecycle: {@code PREPARING -> IN_PROGRESS -> ENDED -> REVIEWING -> SUBMITTED};
 * {@code CANCELLED} is reachable from {@code PREPARING}/{@code IN_PROGRESS}, and
 * {@code FAILED} is reachable from any non-terminal status.
 *
 * <p>Terminal {@code fail}: calling {@link #fail(FailureReason, Instant)} on a terminal session
 * ({@code SUBMITTED}, {@code CANCELLED}, {@code FAILED}) throws {@link DomainException} with
 * {@link ErrorCode#SESSION_STATE_INVALID} and leaves the session unchanged.
 */
public final class Session {
    private final SessionId id;
    private final SessionType type;
    private final TaskId taskId;
    private final CourseId courseId;
    private final AccountId studentAccountId;
    private final TaskMode mode;
    private final int attemptNo;
    private final Instant createdAt;
    private final EnumMap<StreamType, Long> streamOffsets;
    private SessionStatus status;
    private Instant startedAt;
    private Instant endedAt;
    private Long durationMs;
    private Instant updatedAt;

    private Session(
            SessionId id,
            SessionType type,
            TaskId taskId,
            CourseId courseId,
            AccountId studentAccountId,
            TaskMode mode,
            SessionStatus status,
            int attemptNo,
            Instant startedAt,
            Instant endedAt,
            Long durationMs,
            Map<StreamType, Long> streamOffsets,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.type = type;
        this.taskId = taskId;
        this.courseId = courseId;
        this.studentAccountId = studentAccountId;
        this.mode = mode;
        this.status = status;
        this.attemptNo = attemptNo;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.durationMs = durationMs;
        this.streamOffsets = copyOffsets(streamOffsets);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Session createPreparing(
            SessionId id,
            SessionType type,
            TaskId taskId,
            CourseId courseId,
            AccountId studentAccountId,
            TaskMode mode,
            int attemptNo,
            Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(studentAccountId, "studentAccountId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(now, "now");
        requirePositiveAttempt(attemptNo);
        return new Session(id, type, taskId, courseId, studentAccountId, mode,
                SessionStatus.PREPARING, attemptNo, null, null, null, Map.of(), now, now);
    }

    /**
     * Rebuilds a session from persisted fields. {@code courseId} is nullable because the
     * {@code sessions.course_id} foreign key is {@code ON DELETE SET NULL}.
     */
    public static Session rehydrate(
            SessionId id,
            SessionType type,
            TaskId taskId,
            CourseId courseId,
            AccountId studentAccountId,
            TaskMode mode,
            SessionStatus status,
            int attemptNo,
            Instant startedAt,
            Instant endedAt,
            Long durationMs,
            Map<StreamType, Long> streamOffsets,
            Instant createdAt,
            Instant updatedAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(studentAccountId, "studentAccountId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(streamOffsets, "streamOffsets");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        requirePositiveAttempt(attemptNo);
        if (startedAt != null && endedAt != null && endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not precede startedAt");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        return new Session(id, type, taskId, courseId, studentAccountId, mode, status, attemptNo,
                startedAt, endedAt, durationMs, streamOffsets, createdAt, updatedAt);
    }

    public void start(Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatus("start", SessionStatus.PREPARING);
        this.status = SessionStatus.IN_PROGRESS;
        this.startedAt = now;
        this.updatedAt = now;
    }

    public void end(Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatus("end", SessionStatus.IN_PROGRESS);
        if (now.isBefore(startedAt)) {
            throw stateInvalid("end", "结束时间早于开始时间");
        }
        this.status = SessionStatus.ENDED;
        this.endedAt = now;
        this.durationMs = Duration.between(startedAt, now).toMillis();
        this.updatedAt = now;
    }

    public void markReviewing(Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatus("markReviewing", SessionStatus.ENDED);
        this.status = SessionStatus.REVIEWING;
        this.updatedAt = now;
    }

    public void submit(Instant now) {
        Objects.requireNonNull(now, "now");
        requireAnyStatus("submit", SessionStatus.ENDED, SessionStatus.REVIEWING);
        this.status = SessionStatus.SUBMITTED;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        Objects.requireNonNull(now, "now");
        requireAnyStatus("cancel", SessionStatus.PREPARING, SessionStatus.IN_PROGRESS);
        this.status = SessionStatus.CANCELLED;
        this.updatedAt = now;
    }

    public void fail(FailureReason reason, Instant now) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(now, "now");
        if (status == SessionStatus.SUBMITTED
                || status == SessionStatus.CANCELLED
                || status == SessionStatus.FAILED) {
            throw stateInvalid("fail", "会话已处于终态");
        }
        this.status = SessionStatus.FAILED;
        this.updatedAt = now;
    }

    public void putStreamOffset(StreamType type, long offsetMs) {
        Objects.requireNonNull(type, "type");
        if (offsetMs < 0) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "录制偏移不能为负",
                    Map.of("field", "offset_ms"));
        }
        this.streamOffsets.put(type, offsetMs);
    }

    public SessionId id() {
        return id;
    }

    public SessionType type() {
        return type;
    }

    public TaskId taskId() {
        return taskId;
    }

    public CourseId courseId() {
        return courseId;
    }

    public AccountId studentAccountId() {
        return studentAccountId;
    }

    public TaskMode mode() {
        return mode;
    }

    public SessionStatus status() {
        return status;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public Long durationMs() {
        return durationMs;
    }

    public Map<StreamType, Long> streamOffsets() {
        return Collections.unmodifiableMap(new EnumMap<>(streamOffsets));
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private void requireStatus(String action, SessionStatus expected) {
        requireAnyStatus(action, expected);
    }

    private void requireAnyStatus(String action, SessionStatus... allowed) {
        for (SessionStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw stateInvalid(action, "当前状态不允许该操作");
    }

    private DomainException stateInvalid(String action, String message) {
        return new DomainException(ErrorCode.SESSION_STATE_INVALID, message,
                Map.of("action", action, "status", status.wireValue()));
    }

    private static void requirePositiveAttempt(int attemptNo) {
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
    }

    private static EnumMap<StreamType, Long> copyOffsets(Map<StreamType, Long> source) {
        EnumMap<StreamType, Long> copy = new EnumMap<>(StreamType.class);
        for (Map.Entry<StreamType, Long> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "stream type"),
                    Objects.requireNonNull(entry.getValue(), "stream offset"));
        }
        return copy;
    }
}
