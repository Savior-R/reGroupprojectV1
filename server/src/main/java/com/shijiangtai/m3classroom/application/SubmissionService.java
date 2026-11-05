package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.view.SubmissionView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;

/** Submission transaction for a finished task session (05B §6.9). */
public interface SubmissionService {

    /**
     * Flushes the live actor, validates the required streams, then writes the submission, the
     * submitted session, the submitted assignment and the {@code submission.created} event in one
     * transaction. Cleanup of superseded attempts is deliberately a separate call that the caller
     * makes only after this method returns and the transaction has committed.
     */
    SubmissionView submit(CurrentUser student, SessionId sessionId);

    /** Deletes recording rows and objects of superseded attempts, keeping {@code keep}. */
    void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep);
}
