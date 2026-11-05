package com.shijiangtai.m3classroom.domain.submission;

import java.time.Instant;
import java.util.UUID;

/**
 * Integration event published after a submission commits.
 *
 * <p>M4 creates the evaluation from it and M7 refreshes its grading projections. It is emitted
 * through {@code EventPublisher}, which defers publication to after commit.
 */
public record SubmissionCreatedEvent(
        UUID submissionId,
        UUID taskId,
        UUID sessionId,
        UUID studentAccountId,
        UUID classIdSnapshot,
        Instant submittedAt) {
}
