package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;

/**
 * Recording-side effects of session lifecycle operations.
 *
 * <p>Implemented by {@code RecordingLifecycleService}, which aborts multipart uploads and
 * deletes superseded attempts on behalf of the session service.
 */
public interface RecordingLifecyclePort {

    /** Aborts every in-flight upload and removes the temporary recording of the session. */
    void abortAll(SessionId sessionId);

    /**
     * Deletes one superseded attempt (session + recordings/streams + objects). Submitted and
     * active attempts are left untouched.
     */
    void deleteAttempt(SessionId sessionId);

    /** Deletes recordings/texts of earlier attempts, keeping the given session intact. */
    void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep);

    /**
     * Deletes a temporary task session whose 24-hour retention has expired. Objects are deleted
     * before the recording and session rows; a failed object delete propagates and leaves every
     * row intact for the next cleanup run.
     *
     * @return {@code true} only when this call deleted the session
     */
    boolean deleteExpiredTemporary(SessionId sessionId);

    /**
     * Deletes a submitted session after its course retention expired: objects first, then the
     * submission, recording, and session rows.
     *
     * @return {@code true} only when this call deleted the submission/session
     */
    boolean deleteSubmittedSession(SessionId sessionId);
}
