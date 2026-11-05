package com.shijiangtai.m3classroom.application.view;

import java.util.UUID;

/** Submission result returned by {@code POST /sessions/{id}/submit}; snake_case on the wire. */
public record SubmissionView(
        UUID submissionId,
        UUID taskId,
        UUID sessionId,
        UUID studentAccountId,
        String status,
        String submittedAt) {
}
