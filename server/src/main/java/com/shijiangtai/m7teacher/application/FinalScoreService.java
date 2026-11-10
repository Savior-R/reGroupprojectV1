package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.domain.AiEvaluationSnapshot;
import com.shijiangtai.m7teacher.domain.FinalScore;
import com.shijiangtai.m7teacher.domain.FinalScoreCalculator;
import com.shijiangtai.m7teacher.domain.FinalScoreInput;
import com.shijiangtai.m7teacher.domain.FinalScoreRepository;
import com.shijiangtai.m7teacher.domain.FinalScoreResult;
import com.shijiangtai.m7teacher.domain.FinalScoreStatus;
import com.shijiangtai.m7teacher.domain.GradingConfig;
import com.shijiangtai.m7teacher.domain.GradingConfigRepository;
import com.shijiangtai.m7teacher.domain.M7Exception;
import com.shijiangtai.m7teacher.domain.Rubric;
import com.shijiangtai.m7teacher.domain.TeacherScore;
import com.shijiangtai.m7teacher.domain.TeacherScoreRepository;
import com.shijiangtai.shared.application.ClockProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Final-score composition and recomputation (07 §6 / FR-M7-04).
 *
 * <p>Every recomputation is idempotent: the row is keyed by {@code submission_id} and the result
 * is a pure function of the M3 submission, the M4 evaluation and the effective configuration.
 */
@Service
public class FinalScoreService {

    private final FinalScoreRepository finalScoreRepository;
    private final TeacherScoreRepository teacherScoreRepository;
    private final GradingConfigRepository configRepository;
    private final UpstreamQueryPort upstream;
    private final FinalScoreCalculator calculator;
    private final ClockProvider clockProvider;

    public FinalScoreService(
            FinalScoreRepository finalScoreRepository,
            TeacherScoreRepository teacherScoreRepository,
            GradingConfigRepository configRepository,
            UpstreamQueryPort upstream,
            FinalScoreCalculator calculator,
            ClockProvider clockProvider) {
        this.finalScoreRepository = finalScoreRepository;
        this.teacherScoreRepository = teacherScoreRepository;
        this.configRepository = configRepository;
        this.upstream = upstream;
        this.calculator = calculator;
        this.clockProvider = clockProvider;
    }

    /** Recompute and persist one submission; returns null when upstream rows are missing. */
    @Transactional
    public FinalScore recompute(UUID submissionId) {
        return doRecompute(submissionId);
    }

    /** Recompute every submission of a task (configuration change / clear scores). */
    @Transactional
    public int recomputeTask(UUID taskId) {
        int count = 0;
        for (UpstreamQueryPort.Submission submission : upstream.findSubmissionsByTask(taskId)) {
            if (doRecompute(submission.id()) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Current calculation without writing, used by the student result page and the roster so a
     * stale cached row never hides a newly completed AI evaluation.
     */
    public FinalScoreResult preview(SubmissionAccess access) {
        Calculation calculation = calculate(access.submission().id(), access.taskContext());
        return calculation == null
                ? FinalScoreResult.pending(FinalScoreStatus.PENDING_AI, null, null,
                        "DATA_INCONSISTENT", "提交件或课程数据缺失")
                : calculation.result();
    }

    /** Lost-event / stale-row detection used by the scheduled reconciler (07 §6.4). */
    public boolean stale(UUID submissionId) {
        Optional<FinalScore> existing = finalScoreRepository.findBySubmissionId(submissionId);
        if (existing.isEmpty()) {
            return true;
        }
        FinalScore finalScore = existing.get();
        UpstreamQueryPort.Submission submission = upstream.findSubmission(submissionId).orElse(null);
        if (submission == null) {
            return false;
        }
        UpstreamQueryPort.Task task = upstream.findTask(submission.taskId()).orElse(null);
        if (task == null) {
            return false;
        }
        GradingConfig config = configRepository.findByTaskId(task.id()).orElse(null);
        int configVersion = config == null ? 1 : config.configVersion();
        if (finalScore.configVersion() != configVersion) {
            return true;
        }
        UpstreamQueryPort.Evaluation evaluation = upstream
                .findEvaluationBySubmission(submissionId).orElse(null);
        if (evaluation != null && evaluation.completedAt() != null
                && evaluation.completedAt().isAfter(finalScore.computedAt())) {
            return true;
        }
        TeacherScore teacher = teacherScoreRepository.findBySubmissionId(submissionId).orElse(null);
        return teacher != null && teacher.updatedAt().isAfter(finalScore.computedAt());
    }

    private FinalScore doRecompute(UUID submissionId) {
        Calculation calculation = calculate(submissionId, null);
        if (calculation == null) {
            return null;
        }
        FinalScoreResult result = calculation.result();
        Instant now = clockProvider.now();
        FinalScore row = new FinalScore(
                calculation.submission().id(),
                calculation.task().id(),
                calculation.course().id(),
                calculation.submission().studentAccountId(),
                calculation.evaluation() == null ? null : calculation.evaluation().id(),
                calculation.teacher() == null ? null : calculation.teacher().id(),
                result.aiTotal(),
                result.teacherTotal(),
                result.finalTotal(),
                result.finalDimensions(),
                result.status(),
                calculation.classIdSnapshot(),
                calculation.config().configVersion(),
                result.errorCode(),
                result.errorMessage(),
                now);
        return finalScoreRepository.upsert(row);
    }

    private Calculation calculate(UUID submissionId, TaskContext knownContext) {
        UpstreamQueryPort.Submission submission = upstream.findSubmission(submissionId)
                .orElse(null);
        if (submission == null) {
            return null;
        }
        UpstreamQueryPort.Task task = knownContext == null
                ? upstream.findTask(submission.taskId()).orElse(null)
                : knownContext.task();
        if (task == null) {
            return null;
        }
        UpstreamQueryPort.Course course = knownContext == null
                ? upstream.findCourse(task.courseId()).orElse(null)
                : knownContext.course();
        if (course == null) {
            return null;
        }

        GradingConfig config = configRepository.findByTaskId(task.id())
                .orElseGet(() -> GradingConfig.defaults(UUID.randomUUID(), task.id(),
                        course.id(), task.id(), clockProvider.now()));
        TeacherScore teacher = teacherScoreRepository.findBySubmissionId(submissionId).orElse(null);
        UpstreamQueryPort.Evaluation evaluation = upstream
                .findEvaluationBySubmission(submissionId).orElse(null);
        AiEvaluationSnapshot ai = evaluation == null ? null : new AiEvaluationSnapshot(
                evaluation.id(), evaluation.status(), evaluation.totalScore(),
                evaluation.dimensionScores(), evaluation.errorCode(), evaluation.errorMessage());

        String dataErrorCode = null;
        String dataErrorMessage = null;
        Rubric rubric;
        try {
            rubric = knownContext == null
                    ? Rubric.fromSnapshot(task.mode(), task.rubricWeights())
                    : knownContext.rubric();
        } catch (M7Exception exception) {
            rubric = Rubric.fromSnapshot(task.mode(), Map.of());
            dataErrorCode = exception.code().name();
            dataErrorMessage = exception.getMessage();
        }

        FinalScoreInput input = new FinalScoreInput(
                config.aiWeight(), config.teacherWeight(), config.scoringMode(),
                rubric, ai, teacher, dataErrorCode, dataErrorMessage);
        FinalScoreResult result = calculator.calculate(input);

        FinalScore existing = finalScoreRepository.findBySubmissionId(submissionId).orElse(null);
        UUID classIdSnapshot = resolveClassSnapshot(submission, teacher, existing);
        return new Calculation(submission, task, course, config, teacher, evaluation,
                result, classIdSnapshot);
    }

    private UUID resolveClassSnapshot(
            UpstreamQueryPort.Submission submission,
            TeacherScore teacher,
            FinalScore existing) {
        if (submission.classIdSnapshot() != null) {
            return submission.classIdSnapshot();
        }
        if (teacher != null && teacher.classIdSnapshot() != null) {
            return teacher.classIdSnapshot();
        }
        if (existing != null && existing.classIdSnapshot() != null) {
            return existing.classIdSnapshot();
        }
        UpstreamQueryPort.Assignment assignment = upstream
                .findAssignment(submission.taskId(), submission.studentAccountId())
                .orElse(null);
        if (assignment != null && assignment.classIdSnapshot() != null) {
            return assignment.classIdSnapshot();
        }
        return upstream.findAccounts(List.of(submission.studentAccountId()))
                .get(submission.studentAccountId()) instanceof UpstreamQueryPort.Account account
                ? account.classId()
                : null;
    }

    private record Calculation(
            UpstreamQueryPort.Submission submission,
            UpstreamQueryPort.Task task,
            UpstreamQueryPort.Course course,
            GradingConfig config,
            TeacherScore teacher,
            UpstreamQueryPort.Evaluation evaluation,
            FinalScoreResult result,
            UUID classIdSnapshot) {
    }
}
