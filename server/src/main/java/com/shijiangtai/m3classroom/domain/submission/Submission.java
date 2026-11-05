package com.shijiangtai.m3classroom.domain.submission;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A student's submission for a task (01 §4, 05B §6.9).
 *
 * <p>Submissions are immutable snapshots: one row per {@code (task, student)} and one row per
 * session. The class snapshot is nullable because the class may be deleted after the submission;
 * it is kept for M7 projections. The only status this round is {@link #STATUS_SUBMITTED}.
 */
public record Submission(
        UUID id,
        TaskId taskId,
        AccountId studentAccountId,
        SessionId sessionId,
        ClassId classIdSnapshot,
        Instant submittedAt,
        String status) {

    public static final String STATUS_SUBMITTED = "submitted";

    public Submission {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(studentAccountId, "studentAccountId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(submittedAt, "submittedAt");
        Objects.requireNonNull(status, "status");
        if (status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
    }

    public static Submission create(
            UUID id,
            TaskId taskId,
            AccountId student,
            SessionId sessionId,
            ClassId classIdSnapshot,
            Instant now) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(now, "now");
        return new Submission(
                id, taskId, student, sessionId, classIdSnapshot, now, STATUS_SUBMITTED);
    }
}
