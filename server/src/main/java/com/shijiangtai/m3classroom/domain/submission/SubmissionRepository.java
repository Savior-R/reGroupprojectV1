package com.shijiangtai.m3classroom.domain.submission;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for {@link Submission} (01 §4). */
public interface SubmissionRepository {

    Submission save(Submission submission);

    Optional<Submission> findByTaskAndStudent(TaskId taskId, AccountId studentAccountId);

    Optional<Submission> findBySession(SessionId sessionId);

    boolean existsBySession(SessionId sessionId);

    /**
     * Submissions whose course ended at least one year before {@code now}
     * ({@code courses.end_at + INTERVAL '1 year' <= now}). A null course end is never a
     * candidate.
     *
     * <p>Defaults to failing fast so any adapter or fake used for retention queries must opt in;
     * a silent empty result would make submitted-recording cleanup a permanent no-op. The JPA
     * adapter and {@code InMemorySubmissionRepository} override it.
     */
    default List<Submission> findExpiredForRetention(Instant now) {
        throw new UnsupportedOperationException("findExpiredForRetention is not implemented");
    }

    /**
     * Deletes the submission row for the session, releasing the {@code ON DELETE RESTRICT}
     * foreign key before the session row is deleted. The JPA adapter flushes so the following
     * session delete sees the constraint released.
     *
     * <p>Defaults to failing fast so any fake used for submitted-session cleanup must opt in.
     */
    default void deleteBySession(SessionId sessionId) {
        throw new UnsupportedOperationException("deleteBySession is not implemented");
    }
}
