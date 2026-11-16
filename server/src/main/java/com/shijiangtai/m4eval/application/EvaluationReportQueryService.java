package com.shijiangtai.m4eval.application;

import com.shijiangtai.m4eval.application.port.DimensionScoreRepository;
import com.shijiangtai.m4eval.application.port.EvaluationInputSnapshotRepository;
import com.shijiangtai.m4eval.application.port.EvaluationIssueRepository;
import com.shijiangtai.m4eval.application.port.EvaluationRepository;
import com.shijiangtai.m4eval.application.view.IssueView;
import com.shijiangtai.m4eval.application.view.ReportView;
import com.shijiangtai.m4eval.domain.DimensionScore;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import com.shijiangtai.m4eval.domain.EvaluationIssue;
import com.shijiangtai.m4eval.domain.EvaluationStatus;
import com.shijiangtai.m4eval.domain.InputSnapshot;
import com.shijiangtai.shared.application.CurrentUser;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Report read model (05 §4.3, 06 §9).
 *
 * <p>{@code pending / running} → 409, {@code failed} → 422, {@code partial} → 200 without a total.
 */
@Service
public class EvaluationReportQueryService {

    private static final int MAX_SUGGESTIONS = 5;

    private final EvaluationRepository evaluationRepository;
    private final DimensionScoreRepository dimensionScoreRepository;
    private final EvaluationIssueRepository issueRepository;
    private final EvaluationInputSnapshotRepository snapshotRepository;
    private final EvaluationAccessPolicy accessPolicy;

    public EvaluationReportQueryService(
            EvaluationRepository evaluationRepository,
            DimensionScoreRepository dimensionScoreRepository,
            EvaluationIssueRepository issueRepository,
            EvaluationInputSnapshotRepository snapshotRepository,
            EvaluationAccessPolicy accessPolicy) {
        this.evaluationRepository = Objects.requireNonNull(
                evaluationRepository, "evaluationRepository");
        this.dimensionScoreRepository = Objects.requireNonNull(
                dimensionScoreRepository, "dimensionScoreRepository");
        this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
        this.snapshotRepository = Objects.requireNonNull(
                snapshotRepository, "snapshotRepository");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    @Transactional(readOnly = true)
    public ReportView report(CurrentUser user, UUID evaluationId) {
        Evaluation evaluation = requireReadable(user, evaluationId);
        requireReady(evaluation);
        List<DimensionScore> scores = dimensionScoreRepository.findByEvaluationId(evaluationId);
        List<IssueView> suggestions = issueRepository.findByEvaluationId(evaluationId).stream()
                .filter(EvaluationIssue::isSuggestion)
                .sorted(Comparator
                        .comparing((EvaluationIssue issue) -> rank(issue))
                        .thenComparing(issue -> issue.startMs() == null
                                ? Long.MAX_VALUE : issue.startMs()))
                .limit(MAX_SUGGESTIONS)
                .map(IssueView::from)
                .toList();
        Integer configVersion = snapshotRepository.findByEvaluationId(evaluationId)
                .map(InputSnapshot::configVersion)
                .orElse(null);
        return ReportView.build(evaluation, scores, suggestions, configVersion);
    }

    @Transactional(readOnly = true)
    public List<IssueView> issues(CurrentUser user, UUID evaluationId) {
        Evaluation evaluation = requireReadable(user, evaluationId);
        requireReady(evaluation);
        return issueRepository.findByEvaluationId(evaluationId).stream()
                .sorted(Comparator
                        .comparing(EvaluationIssue::sortOrder)
                        .thenComparing(issue -> issue.startMs() == null
                                ? Long.MAX_VALUE : issue.startMs()))
                .map(IssueView::from)
                .toList();
    }

    /** Status-only access for {@code /evaluations/{id}}; misses are 404. */
    @Transactional(readOnly = true)
    public Evaluation requireReadable(CurrentUser user, UUID evaluationId) {
        Evaluation evaluation = evaluationRepository
                .findById(evaluationId)
                .orElseThrow(() -> new EvaluationException(
                        EvaluationErrorCode.EVALUATION_NOT_FOUND, "评测不存在"));
        accessPolicy.requireRead(user, evaluation);
        return evaluation;
    }

    private static void requireReady(Evaluation evaluation) {
        if (evaluation.status() == EvaluationStatus.PENDING
                || evaluation.status() == EvaluationStatus.RUNNING) {
            throw new EvaluationException(
                    EvaluationErrorCode.EVALUATION_NOT_READY,
                    "评测尚未完成",
                    Map.of("status", evaluation.status().wireValue()));
        }
        if (evaluation.status() == EvaluationStatus.FAILED) {
            throw new EvaluationException(
                    EvaluationErrorCode.EVALUATION_FAILED,
                    "评测失败",
                    Map.of(
                            "error_code", evaluation.errorCode() == null
                                    ? "EVALUATION_FAILED" : evaluation.errorCode(),
                            "partial_reasons", evaluation.partialReasons()));
        }
    }

    private static int rank(EvaluationIssue issue) {
        return issue.suggestionRank() == null ? Integer.MAX_VALUE : issue.suggestionRank();
    }
}
