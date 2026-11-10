package com.shijiangtai.m4eval.application.port;

import com.shijiangtai.m4eval.domain.InputSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface EvaluationInputSnapshotRepository {

    InputSnapshot save(InputSnapshot snapshot);

    Optional<InputSnapshot> findByEvaluationId(UUID evaluationId);
}
