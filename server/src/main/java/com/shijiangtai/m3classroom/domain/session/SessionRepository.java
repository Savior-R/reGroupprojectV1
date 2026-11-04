package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link Session}. Task 9 provides the JPA adapter.
 *
 * <p>{@link #findActiveByStudentAndTask(AccountId, TaskId)} considers a session active while it
 * is {@code preparing} or {@code in_progress}.
 */
public interface SessionRepository {

    Session save(Session session);

    Optional<Session> findById(SessionId id);

    Optional<Session> findActiveByStudentAndTask(AccountId studentAccountId, TaskId taskId);

    List<Session> findByStudentAndTask(AccountId studentAccountId, TaskId taskId);

    long countByStudentAndTask(AccountId studentAccountId, TaskId taskId);
}
