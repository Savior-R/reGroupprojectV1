package com.shijiangtai.m3classroom.domain.submission;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.Optional;

/** Persistence port for {@link Submission} (01 §4). */
public interface SubmissionRepository {

    Submission save(Submission submission);

    Optional<Submission> findByTaskAndStudent(TaskId taskId, AccountId studentAccountId);

    Optional<Submission> findBySession(SessionId sessionId);

    boolean existsBySession(SessionId sessionId);
}
