package com.shijiangtai.m4eval.application.port;

import com.shijiangtai.m4eval.domain.EvaluationJob;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvaluationJobRepository {

    EvaluationJob save(EvaluationJob job);

    Optional<EvaluationJob> findById(UUID jobId);

    Optional<EvaluationJob> findByEvaluationId(UUID evaluationId);

    /** Queued jobs whose retry time has arrived, oldest first. */
    List<EvaluationJob> findDispatchable(Instant now, int limit);

    /** Running jobs whose worker has not reported for {@code staleBefore}. */
    List<EvaluationJob> findStaleRunning(Instant staleBefore, int limit);
}
