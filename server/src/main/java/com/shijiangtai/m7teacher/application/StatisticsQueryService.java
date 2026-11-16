package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.application.view.CourseStatsSummary;
import com.shijiangtai.m7teacher.application.view.CourseStatsView;
import com.shijiangtai.m7teacher.application.view.CourseTaskStat;
import com.shijiangtai.m7teacher.application.view.DimensionStat;
import com.shijiangtai.m7teacher.application.view.StatsSummary;
import com.shijiangtai.m7teacher.application.view.TaskStatsView;
import com.shijiangtai.m7teacher.domain.FinalScore;
import com.shijiangtai.m7teacher.domain.FinalScoreRepository;
import com.shijiangtai.m7teacher.domain.FinalScoreStatus;
import com.shijiangtai.shared.application.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Task / class / course statistics (FR-M7-05 / 07 §7).
 *
 * <p>Only {@code completed} final scores enter averages, dimension statistics and weak items;
 * pending / partial / failed rows are counted separately.
 */
@Service
public class StatisticsQueryService {

    private static final int WEAK_DIMENSION_COUNT = 2;
    private static final int SAMPLE_THRESHOLD = 3;
    private static final BigDecimal WEAK_THRESHOLD = BigDecimal.valueOf(60);

    private final M7AccessPolicy accessPolicy;
    private final UpstreamQueryPort upstream;
    private final FinalScoreRepository finalScoreRepository;

    public StatisticsQueryService(
            M7AccessPolicy accessPolicy,
            UpstreamQueryPort upstream,
            FinalScoreRepository finalScoreRepository) {
        this.accessPolicy = accessPolicy;
        this.upstream = upstream;
        this.finalScoreRepository = finalScoreRepository;
    }

    public TaskStatsView taskStats(CurrentUser user, UUID taskId, UUID classId) {
        TaskContext context = accessPolicy.requireTeacherTask(user, taskId);
        accessPolicy.requirePublished(context);

        List<UpstreamQueryPort.Assignment> assignments = upstream.findAssignments(taskId);
        Map<UUID, UpstreamQueryPort.Submission> submissionByStudent = new HashMap<>();
        for (UpstreamQueryPort.Submission submission : upstream.findSubmissionsByTask(taskId)) {
            submissionByStudent.putIfAbsent(submission.studentAccountId(), submission);
        }
        Map<UUID, UpstreamQueryPort.Session> sessionByStudent = new HashMap<>();
        for (UpstreamQueryPort.Session session : upstream.findSessionsByTask(taskId)) {
            sessionByStudent.putIfAbsent(session.studentAccountId(), session);
        }
        Map<UUID, FinalScore> finalBySubmission = finalScoreRepository.findBySubmissionIds(
                submissionByStudent.values().stream()
                        .map(UpstreamQueryPort.Submission::id).toList());
        Map<UUID, FinalScore> finalByStudent = new HashMap<>();
        submissionByStudent.forEach((studentId, submission) -> {
            FinalScore score = finalBySubmission.get(submission.id());
            if (score != null) {
                finalByStudent.put(studentId, score);
            }
        });
        Map<UUID, UpstreamQueryPort.Account> accounts = upstream.findAccounts(
                assignments.stream().map(UpstreamQueryPort.Assignment::studentAccountId).toList());
        int assigned = 0;
        int submitted = 0;
        int notSubmitted = 0;
        int inProgress = 0;
        int completed = 0;
        int pendingAi = 0;
        int pendingTeacher = 0;
        int aiPartial = 0;
        int aiFailed = 0;
        BigDecimal totalSum = BigDecimal.ZERO;
        int totalCount = 0;
        Map<String, BigDecimal> dimensionSums = new LinkedHashMap<>();
        Map<String, Integer> dimensionCounts = new LinkedHashMap<>();
        for (String dimension : context.rubric().dimensions()) {
            dimensionSums.put(dimension, BigDecimal.ZERO);
            dimensionCounts.put(dimension, 0);
        }

        for (UpstreamQueryPort.Assignment assignment : assignments) {
            UpstreamQueryPort.Submission submission =
                    submissionByStudent.get(assignment.studentAccountId());
            FinalScore finalScore = finalByStudent.get(assignment.studentAccountId());
            UUID effectiveClass = effectiveClass(assignment, submission, finalScore, accounts);
            if (classId != null && !classId.equals(effectiveClass)) {
                continue;
            }
            assigned++;
            if (submission == null) {
                notSubmitted++;
                UpstreamQueryPort.Session session = sessionByStudent.get(
                        assignment.studentAccountId());
                if (session != null && ("preparing".equals(session.status())
                        || "in_progress".equals(session.status()))) {
                    inProgress++;
                }
                continue;
            }
            submitted++;
            if (finalScore == null) {
                pendingAi++;
                continue;
            }
            switch (finalScore.status()) {
                case COMPLETED -> {
                    completed++;
                    totalSum = totalSum.add(finalScore.finalTotal());
                    totalCount++;
                    for (Map.Entry<String, BigDecimal> entry
                            : finalScore.finalDimensions().entrySet()) {
                        if (dimensionSums.containsKey(entry.getKey())) {
                            dimensionSums.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
                            dimensionCounts.merge(entry.getKey(), 1, Integer::sum);
                        }
                    }
                }
                case PENDING_AI -> pendingAi++;
                case PENDING_TEACHER -> pendingTeacher++;
                case AI_PARTIAL -> aiPartial++;
                case AI_FAILED -> aiFailed++;
            }
        }

        BigDecimal averageFinal = totalCount == 0 ? null
                : totalSum.divide(BigDecimal.valueOf(totalCount), 1, RoundingMode.HALF_UP);

        List<DimensionStat> dimensions = new ArrayList<>();
        for (String dimension : context.rubric().dimensions()) {
            int samples = dimensionCounts.getOrDefault(dimension, 0);
            BigDecimal average = samples == 0 ? null
                    : dimensionSums.get(dimension)
                            .divide(BigDecimal.valueOf(samples), 1, RoundingMode.HALF_UP);
            dimensions.add(new DimensionStat(dimension, average, samples,
                    average != null && average.compareTo(WEAK_THRESHOLD) < 0));
        }
        List<String> weakDimensions = dimensions.stream()
                .filter(stat -> stat.sampleCount() > 0 && stat.average() != null)
                .sorted(Comparator.comparing(DimensionStat::average))
                .limit(WEAK_DIMENSION_COUNT)
                .map(DimensionStat::dimension)
                .toList();

        StatsSummary summary = new StatsSummary(assigned, submitted, notSubmitted, completed,
                pendingAi, pendingTeacher, aiPartial, aiFailed, inProgress, averageFinal);
        return new TaskStatsView(taskId, context.course().id(), classId, summary, dimensions,
                weakDimensions, completed >= SAMPLE_THRESHOLD);
    }

    public CourseStatsView courseStats(CurrentUser user, UUID courseId, UUID classId) {
        TaskContext courseContext = accessPolicy.requireTeacherCourse(user, courseId);
        List<CourseTaskStat> taskRows = new ArrayList<>();
        int assignedTotal = 0;
        int submittedTotal = 0;
        int completedTotal = 0;
        BigDecimal pooledSum = BigDecimal.ZERO;
        int pooledCount = 0;

        List<UpstreamQueryPort.Task> tasks = upstream.findTasksByCourse(courseId).stream()
                .filter(task -> !"draft".equals(task.status()))
                .toList();
        for (UpstreamQueryPort.Task task : tasks) {
            List<UpstreamQueryPort.Assignment> assignments =
                    upstream.findAssignments(task.id());
            Map<UUID, UpstreamQueryPort.Submission> submissionByStudent = new HashMap<>();
            for (UpstreamQueryPort.Submission submission
                    : upstream.findSubmissionsByTask(task.id())) {
                submissionByStudent.putIfAbsent(submission.studentAccountId(), submission);
            }
            Map<UUID, FinalScore> finalBySubmission = finalScoreRepository.findBySubmissionIds(
                    submissionByStudent.values().stream()
                            .map(UpstreamQueryPort.Submission::id).toList());
            Map<UUID, FinalScore> finalByStudent = new HashMap<>();
            submissionByStudent.forEach((studentId, submission) -> {
                FinalScore score = finalBySubmission.get(submission.id());
                if (score != null) {
                    finalByStudent.put(studentId, score);
                }
            });
            Map<UUID, UpstreamQueryPort.Account> accounts = upstream.findAccounts(
                    assignments.stream().map(UpstreamQueryPort.Assignment::studentAccountId)
                            .toList());

            int assigned = 0;
            int submitted = 0;
            int completed = 0;
            BigDecimal taskSum = BigDecimal.ZERO;
            for (UpstreamQueryPort.Assignment assignment : assignments) {
                UpstreamQueryPort.Submission submission =
                        submissionByStudent.get(assignment.studentAccountId());
                FinalScore finalScore = finalByStudent.get(assignment.studentAccountId());
                UUID effectiveClass = effectiveClass(assignment, submission, finalScore, accounts);
                if (classId != null && !classId.equals(effectiveClass)) {
                    continue;
                }
                assigned++;
                if (submission != null) {
                    submitted++;
                }
                if (finalScore != null && finalScore.status() == FinalScoreStatus.COMPLETED) {
                    completed++;
                    taskSum = taskSum.add(finalScore.finalTotal());
                    pooledSum = pooledSum.add(finalScore.finalTotal());
                    pooledCount++;
                }
            }
            BigDecimal taskAverage = completed == 0 ? null
                    : taskSum.divide(BigDecimal.valueOf(completed), 1, RoundingMode.HALF_UP);
            taskRows.add(new CourseTaskStat(task.id(), task.topic(), task.mode(), assigned,
                    submitted, completed, taskAverage));
            assignedTotal += assigned;
            submittedTotal += submitted;
            completedTotal += completed;
        }

        BigDecimal pooledAverage = pooledCount == 0 ? null
                : pooledSum.divide(BigDecimal.valueOf(pooledCount), 1, RoundingMode.HALF_UP);
        CourseStatsSummary summary = new CourseStatsSummary(tasks.size(), assignedTotal,
                submittedTotal, completedTotal, pooledAverage);
        return new CourseStatsView(courseContext.course().id(), classId, summary, taskRows);
    }

    private UUID effectiveClass(
            UpstreamQueryPort.Assignment assignment,
            UpstreamQueryPort.Submission submission,
            FinalScore finalScore,
            Map<UUID, UpstreamQueryPort.Account> accounts) {
        if (assignment.classIdSnapshot() != null) {
            return assignment.classIdSnapshot();
        }
        if (submission != null && submission.classIdSnapshot() != null) {
            return submission.classIdSnapshot();
        }
        if (finalScore != null && finalScore.classIdSnapshot() != null) {
            return finalScore.classIdSnapshot();
        }
        UpstreamQueryPort.Account account = accounts.get(assignment.studentAccountId());
        return account == null ? null : account.classId();
    }
}
