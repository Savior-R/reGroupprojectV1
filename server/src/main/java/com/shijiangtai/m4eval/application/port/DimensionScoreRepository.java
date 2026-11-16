package com.shijiangtai.m4eval.application.port;

import com.shijiangtai.m4eval.domain.DimensionScore;
import java.util.List;
import java.util.UUID;

public interface DimensionScoreRepository {

    void replaceAll(UUID evaluationId, List<DimensionScore> scores);

    List<DimensionScore> findByEvaluationId(UUID evaluationId);
}
