package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.Collection;
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

    /**
     * Loads the session while holding a non-key-update row lock ({@code FOR NO KEY UPDATE}).
     *
     * <p>Recording multipart mutations serialize on this lock so parallel stream starts cannot
     * read the same recording aggregate and overwrite each other. The lock deliberately does not
     * block the foreign-key key-share lock taken by the recording stream inserts.
     */
    Optional<Session> findByIdForUpdate(SessionId id);

    Optional<Session> findActiveByStudentAndTask(AccountId studentAccountId, TaskId taskId);

    List<Session> findByStudentAndTask(AccountId studentAccountId, TaskId taskId);

    /** Sessions in any of the given statuses; used by restart reconciliation. */
    List<Session> findByStatusIn(Collection<SessionStatus> statuses);

    long countByStudentAndTask(AccountId studentAccountId, TaskId taskId);

    /** Deletes a superseded attempt; recordings and derived rows cascade. */
    void deleteById(SessionId id);
}
