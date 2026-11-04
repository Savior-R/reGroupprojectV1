package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link SessionRepository} fake for unit and controller tests (Task 9 adds JPA). */
public final class InMemorySessionRepository implements SessionRepository {

    private final Map<SessionId, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session save(Session session) {
        sessions.put(session.id(), session);
        return session;
    }

    @Override
    public Optional<Session> findById(SessionId id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public Optional<Session> findActiveByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
        return sessions.values().stream()
                .filter(session -> belongsTo(session, studentAccountId, taskId))
                .filter(session -> session.status() == SessionStatus.PREPARING
                        || session.status() == SessionStatus.IN_PROGRESS)
                .findFirst();
    }

    @Override
    public List<Session> findByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
        return sessions.values().stream()
                .filter(session -> belongsTo(session, studentAccountId, taskId))
                .toList();
    }

    @Override
    public long countByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
        return sessions.values().stream()
                .filter(session -> belongsTo(session, studentAccountId, taskId))
                .count();
    }

    public void clear() {
        sessions.clear();
    }

    private static boolean belongsTo(Session session, AccountId studentAccountId, TaskId taskId) {
        return session.studentAccountId().equals(studentAccountId)
                && session.taskId().equals(taskId);
    }
}
