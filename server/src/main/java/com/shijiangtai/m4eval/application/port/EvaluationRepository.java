package com.shijiangtai.m4eval.application.port;

import com.shijiangtai.m4eval.domain.Evaluation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvaluationRepository {

    Evaluation save(Evaluation evaluation);

    Optional<Evaluation> findById(UUID evaluationId);

    Optional<Evaluation> findBySource(String sourceType, UUID sourceId);

    List<UUID> findSubmissionIdsWithoutEvaluation(int limit);

    List<UUID> findTrainingRecordIdsWithoutEvaluation(int limit);
}
