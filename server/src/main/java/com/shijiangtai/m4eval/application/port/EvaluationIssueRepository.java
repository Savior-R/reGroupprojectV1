package com.shijiangtai.m4eval.application.port;

import com.shijiangtai.m4eval.domain.EvaluationIssue;
import java.util.List;
import java.util.UUID;

public interface EvaluationIssueRepository {

    void replaceAll(UUID evaluationId, List<EvaluationIssue> issues);

    List<EvaluationIssue> findByEvaluationId(UUID evaluationId);
}
