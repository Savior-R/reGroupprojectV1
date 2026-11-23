package com.shijiangtai.m4eval.application;

import static com.shijiangtai.m4eval.application.M4TestDoubles.COURSE_ID;
import static com.shijiangtai.m4eval.application.M4TestDoubles.NOW;
import static com.shijiangtai.m4eval.application.M4TestDoubles.SCHOOL_ID;
import static com.shijiangtai.m4eval.application.M4TestDoubles.STUDENT_ID;
import static com.shijiangtai.m4eval.application.M4TestDoubles.SESSION_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryDimensions;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryEvaluations;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemoryIssues;
import com.shijiangtai.m4eval.application.M4TestDoubles.InMemorySnapshots;
import com.shijiangtai.m4eval.application.M4TestDoubles.MutableClock;
import com.shijiangtai.m4eval.application.M4TestDoubles.StubInputQuery;
import com.shijiangtai.m4eval.application.view.ReportView;
import com.shijiangtai.m4eval.domain.DimensionScore;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import com.shijiangtai.m4eval.domain.EvaluationIssue;
import com.shijiangtai.m4eval.domain.EvaluationOutcome;
import com.shijiangtai.m4eval.domain.EvaluationStatus;
import com.shijiangtai.m4eval.domain.InputSnapshot;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.SchoolId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Report read model and status mapping (M4-T05 / T09). */
class EvaluationReportQueryServiceTest {

    private static final UUID EVALUATION_ID =
            UUID.fromString("11111111-aaaa-aaaa-aaaa-111111111111");
    private static final UUID SUBMISSION_ID =
            UUID.fromString("33333333-cccc-cccc-cccc-333333333333");
    private static final UUID TRAINING_RECORD_ID =
            UUID.fromString("44444444-dddd-dddd-dddd-444444444444");
    private static final UUID OTHER_STUDENT_ID =
            UUID.fromString("66666666-ffff-ffff-ffff-666666666666");

    private InMemoryEvaluations evaluations;
    private InMemoryDimensions dimensions;
    private InMemoryIssues issues;
    private InMemorySnapshots snapshots;
    private StubInputQuery inputs;
    private MutableClock clock;
    private EvaluationReportQueryService service;
    private final CurrentUser owner = new CurrentUser(
            new AccountId(STUDENT_ID), new SchoolId(SCHOOL_ID), AccountType.STUDENT);

    @BeforeEach
    void setUp() {
        evaluations = new InMemoryEvaluations();
        dimensions = new InMemoryDimensions();
        issues = new InMemoryIssues();
        snapshots = new InMemorySnapshots();
        inputs = new StubInputQuery();
        inputs.courseSchools.put(COURSE_ID, SCHOOL_ID);
        clock = new MutableClock(NOW);
        service = new EvaluationReportQueryService(
                evaluations,
                dimensions,
                issues,
                snapshots,
                inputs,
                new EvaluationAccessPolicy(inputs),
                clock);
    }

    private Evaluation saveSubmission(EvaluationStatus status, BigDecimal total) {
        Evaluation evaluation = M4TestDoubles.pendingSubmission(EVALUATION_ID, SUBMISSION_ID);
        if (status == EvaluationStatus.COMPLETED) {
            evaluation.apply(new EvaluationOutcome(
                    EvaluationStatus.COMPLETED,
                    total,
                    List.of("EMPTY_EVENTS"),
                    List.of(),
                    "mock-model",
                    "m4-eval-v1",
                    "m4-rubric-v1",
                    Map.of("total_tokens", 50),
                    NOW.minusSeconds(60)));
        } else if (status == EvaluationStatus.PARTIAL) {
            evaluation.apply(new EvaluationOutcome(
                    EvaluationStatus.PARTIAL,
                    null,
                    List.of(),
                    List.of("FRAMES_MISSING"),
                    "mock-model",
                    "m4-eval-v1",
                    "m4-rubric-v1",
                    Map.of(),
                    NOW.minusSeconds(60)));
        } else if (status == EvaluationStatus.FAILED) {
            evaluation.fail("AI_TIMEOUT", "timed out", NOW.minusSeconds(60));
        }
        evaluations.save(evaluation);
        snapshots.save(new InputSnapshot(
                EVALUATION_ID,
                Evaluation.SOURCE_TASK_SUBMISSION,
                SUBMISSION_ID,
                SUBMISSION_ID,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                4,
                4,
                null,
                Map.of(),
                NOW));
        return evaluation;
    }

    private List<DimensionScore> fourDimensions() {
        return List.of(
                M4TestDoubles.dimension(EVALUATION_ID, "content", "82.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "expression", "72.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "interaction", "80.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "time", "90.0"));
    }

    @Test
    void completedReportIncludesTotalRadarAndMeta() {
        saveSubmission(EvaluationStatus.COMPLETED, new BigDecimal("80.8"));
        dimensions.replaceAll(EVALUATION_ID, List.of(
                M4TestDoubles.dimension(EVALUATION_ID, "content", "82.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "expression", "72.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "interaction", "80.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "board", "81.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "time", "90.0")));

        ReportView report = service.report(owner, EVALUATION_ID);

        assertThat(report.status()).isEqualTo("completed");
        assertThat(report.totalScore()).isEqualTo(new BigDecimal("80.8"));
        assertThat(report.radar().axes())
                .containsExactly("content", "expression", "interaction", "board", "time");
        assertThat(report.radar().values()).hasSize(5).doesNotContainNull();
        assertThat(report.dimensions()).hasSize(5);
        assertThat(report.missingDimensions()).isEmpty();
        assertThat(report.warnings()).containsExactly("EMPTY_EVENTS");
        assertThat(report.meta().modelVersion()).isEqualTo("mock-model");
        assertThat(report.meta().configVersion()).isEqualTo(4);
        assertThat(report.meta().completedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void partialReportHasNoTotalAndListsMissingDimensions() {
        saveSubmission(EvaluationStatus.PARTIAL, null);
        dimensions.replaceAll(EVALUATION_ID, fourDimensions());

        ReportView report = service.report(owner, EVALUATION_ID);

        assertThat(report.status()).isEqualTo("partial");
        assertThat(report.totalScore()).isNull();
        assertThat(report.missingDimensions()).containsExactly("board");
        assertThat(report.radar().values()).hasSize(5);
        assertThat(report.radar().values().get(3)).isNull();
        assertThat(report.partialReasons()).containsExactly("FRAMES_MISSING");
    }

    @Test
    void pendingReportReturnsNotReady() {
        saveSubmission(EvaluationStatus.PENDING, null);

        assertThatThrownBy(() -> service.report(owner, EVALUATION_ID))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_NOT_READY));
    }

    @Test
    void failedReportReturnsTheStoredErrorCode() {
        saveSubmission(EvaluationStatus.FAILED, null);

        assertThatThrownBy(() -> service.report(owner, EVALUATION_ID))
                .isInstanceOfSatisfying(EvaluationException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(EvaluationErrorCode.EVALUATION_FAILED);
                    assertThat(exception.details())
                            .containsEntry("error_code", "AI_TIMEOUT")
                            .containsEntry("partial_reasons", List.of());
                });
    }

    @Test
    void suggestionsAreRankedAndCappedAtFive() {
        saveSubmission(EvaluationStatus.COMPLETED, new BigDecimal("80.8"));
        dimensions.replaceAll(EVALUATION_ID, List.of(
                M4TestDoubles.dimension(EVALUATION_ID, "content")));
        List<EvaluationIssue> stored = new ArrayList<>();
        for (int rank = 7; rank >= 1; rank -= 1) {
            stored.add(M4TestDoubles.suggestion(
                    EVALUATION_ID, "content", rank, rank * 1000L));
        }
        issues.replaceAll(EVALUATION_ID, stored);

        ReportView report = service.report(owner, EVALUATION_ID);

        assertThat(report.suggestions()).hasSize(5);
        assertThat(report.suggestions())
                .extracting(issue -> issue.suggestionRank())
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void issuesEndpointKeepsSortOrderAcrossDimensions() {
        saveSubmission(EvaluationStatus.COMPLETED, new BigDecimal("80.8"));
        issues.replaceAll(EVALUATION_ID, List.of(
                M4TestDoubles.suggestion(EVALUATION_ID, "time", 2, 8000)
                        .withSortOrder(2),
                M4TestDoubles.suggestion(EVALUATION_ID, "content", 1, 1000)
                        .withSortOrder(0),
                M4TestDoubles.suggestion(EVALUATION_ID, "board", 1, 5000)
                        .withSortOrder(1)));

        var result = service.issues(owner, EVALUATION_ID);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(issue -> issue.dimension())
                .containsExactly("content", "board", "time");
    }

    @Test
    void nonOwnerStudentGetsForbidden() {
        saveSubmission(EvaluationStatus.COMPLETED, new BigDecimal("80.8"));
        CurrentUser other = new CurrentUser(
                new AccountId(OTHER_STUDENT_ID),
                new SchoolId(SCHOOL_ID),
                AccountType.STUDENT);

        assertThatThrownBy(() -> service.report(other, EVALUATION_ID))
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.code().httpStatus()).isEqualTo(403));
    }

    @Test
    void expiredTrainingReportIsNotFound() {
        saveTrainingReport("selected", NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.reportByTrainingRecord(owner, TRAINING_RECORD_ID))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_NOT_FOUND));
    }

    @Test
    void deletedTrainingReportIsNotFound() {
        saveTrainingReport("deleted", NOW.plusSeconds(86_400));

        assertThatThrownBy(() -> service.reportByTrainingRecord(owner, TRAINING_RECORD_ID))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_NOT_FOUND));
    }

    @Test
    void activeEvaluatedTrainingReportIsReadable() {
        saveTrainingReport("evaluated", NOW.plusSeconds(86_400));
        dimensions.replaceAll(EVALUATION_ID, List.of(
                M4TestDoubles.dimension(EVALUATION_ID, "content", "80.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "expression", "80.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "interaction", "80.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "board", "80.0"),
                M4TestDoubles.dimension(EVALUATION_ID, "time", "80.0")));

        ReportView report = service.reportByTrainingRecord(owner, TRAINING_RECORD_ID);

        assertThat(report.status()).isEqualTo("completed");
        assertThat(report.totalScore()).isEqualTo(new BigDecimal("80.0"));
    }

    private void saveTrainingReport(String recordStatus, Instant expiresAt) {
        Evaluation evaluation = M4TestDoubles.pendingTraining(
                EVALUATION_ID, TRAINING_RECORD_ID);
        evaluation.apply(new EvaluationOutcome(
                EvaluationStatus.COMPLETED,
                new BigDecimal("80.0"),
                List.of(),
                List.of(),
                "mock-model",
                "m4-eval-v1",
                "m4-rubric-v1",
                Map.of(),
                NOW.minusSeconds(60)));
        evaluations.save(evaluation);
        inputs.training = M4TestDoubles.training(
                TRAINING_RECORD_ID, recordStatus, expiresAt);
        inputs.session = M4TestDoubles.session();
        snapshots.save(new InputSnapshot(
                EVALUATION_ID,
                Evaluation.SOURCE_TRAINING_RECORD,
                TRAINING_RECORD_ID,
                null,
                TRAINING_RECORD_ID,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                2,
                null,
                2,
                Map.of(),
                NOW));
    }

    @Test
    void reportForMissingEvaluationIsNotFound() {
        assertThatThrownBy(() -> service.report(owner, EVALUATION_ID))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_NOT_FOUND));
    }

    @Test
    void sessionAndSourceIdentityAreExposedForReplayAnchors() {
        saveSubmission(EvaluationStatus.COMPLETED, new BigDecimal("80.8"));
        dimensions.replaceAll(EVALUATION_ID, fourDimensions());

        ReportView report = service.report(owner, EVALUATION_ID);

        assertThat(report.evaluationId()).isEqualTo(EVALUATION_ID);
        assertThat(report.submissionId()).isEqualTo(SUBMISSION_ID);
        assertThat(report.sessionId()).isEqualTo(SESSION_ID);
        assertThat(report.mode()).isEqualTo("free");
    }
}
