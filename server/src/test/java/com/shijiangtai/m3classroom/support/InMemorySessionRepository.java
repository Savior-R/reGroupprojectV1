package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link SessionRepository} fake for unit and controller tests (Task 9 adds JPA).
 *
 * <p>Like the JPA adapter, {@link #findById} and the query methods return copies and
 * {@link #save} stores a copy. Callers must therefore save a mutated session explicitly, which
 * lets tests detect stale live actors that were bypassed by a repository-level update.
 */
public final class InMemorySessionRepository implements SessionRepository {

    private static final Duration TEMPORARY_RETENTION = Duration.ofHours(24);
    private static final Set<SessionStatus> TEMPORARY_STATUSES = EnumSet.of(
            SessionStatus.ENDED, SessionStatus.REVIEWING,
            SessionStatus.FAILED, SessionStatus.CANCELLED);

    private final Map<SessionId, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session save(Session session) {
        Session copy = copy(session);
        sessions.put(copy.id(), copy);
        return copy;
    }

    @Override
    public Optional<Session> findById(SessionId id) {
        return Optional.ofNullable(sessions.get(id)).map(InMemorySessionRepository::copy);
    }

    @Override
    public Optional<Session> findByIdForUpdate(SessionId id) {
        return findById(id);
    }

    @Override
    public Optional<Session> findActiveByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
        return sessions.values().stream()
                .filter(session -> belongsTo(session, studentAccountId, taskId))
                .filter(session -> session.status() == SessionStatus.PREPARING
                        || session.status() == SessionStatus.IN_PROGRESS)
                .findFirst()
                .map(InMemorySessionRepository::copy);
    }

    @Override
    public List<Session> findByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
        return sessions.values().stream()
                .filter(session -> belongsTo(session, studentAccountId, taskId))
                .sorted(Comparator.comparingInt(Session::attemptNo))
                .map(InMemorySessionRepository::copy)
                .toList();
    }

    @Override
    public List<Session> findByStatusIn(Collection<SessionStatus> statuses) {
        return sessions.values().stream()
                .filter(session -> statuses.contains(session.status()))
                .map(InMemorySessionRepository::copy)
                .toList();
    }

    @Override
    public long countByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
        return sessions.values().stream()
                .filter(session -> belongsTo(session, studentAccountId, taskId))
                .count();
    }

    /**
     * The fake has no task store, so it can only apply {@code max(ended_at, updated_at) + 24h};
     * the task-close extension is covered by the Postgres IT and by
     * {@code RecordingLifecycleServiceTest}, which supplies a {@code TaskRepository} fake.
     */
    @Override
    public List<Session> findExpiredTemporary(Instant now) {
        return sessions.values().stream()
                .filter(session -> session.type() == SessionType.TASK)
                .filter(session -> TEMPORARY_STATUSES.contains(session.status()))
                .filter(session -> {
                    Instant anchor = session.endedAt() != null
                            ? session.endedAt() : session.updatedAt();
                    return !anchor.plus(TEMPORARY_RETENTION).isAfter(now);
                })
                .sorted(Comparator.comparing(session -> session.id().value()))
                .map(InMemorySessionRepository::copy)
                .toList();
    }

    @Override
    public void deleteById(SessionId id) {
        sessions.remove(id);
    }

    public void clear() {
        sessions.clear();
    }

    private static boolean belongsTo(Session session, AccountId studentAccountId, TaskId taskId) {
        return session.studentAccountId().equals(studentAccountId)
                && session.taskId().equals(taskId);
    }

    private static Session copy(Session session) {
        return Session.rehydrate(
                session.id(),
                session.type(),
                session.taskId(),
                session.courseId(),
                session.studentAccountId(),
                session.mode(),
                session.status(),
                session.attemptNo(),
                session.startedAt(),
                session.endedAt(),
                session.durationMs(),
                session.streamOffsets(),
                session.createdAt(),
                session.updatedAt());
    }
}
