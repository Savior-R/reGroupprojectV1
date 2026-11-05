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

    /** Aborts every in-flight recording upload of the session (best effort). */
    void abortAll(SessionId sessionId);

    /** Deletes recordings/texts of earlier attempts, keeping the given session intact. */
    void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep);
}
