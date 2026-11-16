package com.shijiangtai.m4eval.application.port;

import java.time.Instant;
import java.util.UUID;

/** Redis Streams producer (06 §5.1). Failures leave the job {@code queued} for the scheduler. */
public interface EvaluationQueue {

    void publish(EvaluationDispatchMessage message);

    void deadLetter(EvaluationDispatchMessage message, String reason);

    record EvaluationDispatchMessage(
            UUID jobId,
            UUID evaluationId,
            String sourceType,
            UUID sourceId,
            UUID sessionId,
            int attempt,
            Instant publishedAt) {
    }
}
