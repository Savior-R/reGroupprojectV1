package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.application.view.AiScoreView;
import com.shijiangtai.m7teacher.application.view.FinalScoreRow;
import com.shijiangtai.m7teacher.application.view.GradingConfigView;
import com.shijiangtai.m7teacher.application.view.GradingQuery;
import com.shijiangtai.m7teacher.application.view.GradingSubmissionRow;
import com.shijiangtai.m7teacher.application.view.GradingSubmissionView;
import com.shijiangtai.m7teacher.application.view.SubmissionSummary;
import com.shijiangtai.m7teacher.application.view.TeacherScoreRow;
import com.shijiangtai.m7teacher.domain.AiEvaluationSnapshot;
import com.shijiangtai.m7teacher.domain.FinalScore;
import com.shijiangtai.m7teacher.domain.FinalScoreCalculator;
import com.shijiangtai.m7teacher.domain.FinalScoreInput;
import com.shijiangtai.m7teacher.domain.FinalScoreRepository;
import com.shijiangtai.m7teacher.domain.FinalScoreResult;
import com.shijiangtai.m7teacher.domain.ScoringMode;
import com.shijiangtai.m7teacher.domain.TeacherScore;
import com.shijiangtai.m7teacher.domain.TeacherScoreRepository;
import com.shijiangtai.shared.application.CurrentUser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Roster and status projection for the grading page (FR-M7-02 / 07 §11.2).
 *
 * <p>The roster comes from {@code task_assignments}, not the current course students. Class
 * filtering and display use the assignment snapshot first, then the submission / teacher / final
 * snapshots, and only then the student's current class (07 §6.5).
 */
@Service
public class GradingSubmissionQueryService {

    private final M7AccessPolicy accessPolicy;
    private final GradingConfigService configService;
    private final FinalScoreCalculator finalScoreCalculator;
    private final UpstreamQueryPort upstream;
    private final TeacherScoreRepository teacherScoreRepository;
    private final FinalScoreRepository finalScoreRepository;

    public GradingSubmissionQueryService(
            M7AccessPolicy accessPolicy,
            GradingConfigService configService,
            FinalScoreCalculator finalScoreCalculator,
            UpstreamQueryPort upstream,
            TeacherScoreRepository teacherScoreRepository,
            FinalScoreRepository finalScoreRepository) {
        this.accessPolicy = accessPolicy;
        this.configService = configService;
        this.finalScoreCalculator = finalScoreCalculator;
        this.upstream = upstream;
        this.teacherScoreRepository = teacherScoreRepository;
        this.finalScoreRepository = finalScoreRepository;
    }

    public GradingSubmissionView list(CurrentUser user, UUID taskId, GradingQuery query) {
        TaskContext context = accessPolicy.requireTeacherTask(user, taskId);
        accessPolicy.requirePublished(context);
        GradingConfigView config = configService.get(user, taskId);

        List<GradingSubmissionRow> rows = loadRows(context, config);
        List<GradingSubmissionRow> classFiltered = rows.stream()
                .filter(row -> query.classId() == null
                        || query.classId().equals(row.classIdSnapshot()))
                .toList();
        SubmissionSummary summary = summarize(classFiltered);

        List<GradingSubmissionRow> filtered = classFiltered.stream()
                .filter(row -> matchStatus(row, query.status()))
                .filter(row -> matchKeyword(row, query.keyword()))
                .sorted(Comparator
                        .comparing(GradingSubmissionRow::className,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(GradingSubmissionRow::studentName,
                                Comparator.nullsLast(String::compareTo)))
                .toList();

        int from = Math.min((query.page() - 1) * query.size(), filtered.size());
        int to = Math.min(from + query.size(), filtered.size());
        return new GradingSubmissionView(
                taskId,
                context.course().id(),
                context.task().mode(),
                config.scoringMode(),
                config.rubricWeights(),
                summary,
                List.copyOf(filtered.subList(from, to)),
                filtered.size(),
                query.page(),
                query.size());
    }

    /** All filtered rows for CSV export (no pagination). */
    public ExportRows exportRows(
            CurrentUser user, UUID taskId, UUID classId, String status, String keyword) {
        TaskContext context = accessPolicy.requireTeacherTask(user, taskId);
        accessPolicy.requirePublished(context);
        GradingConfigView config = configService.get(user, taskId);
        List<GradingSubmissionRow> rows = loadRows(context, config).stream()
                .filter(row -> classId == null || classId.equals(row.classIdSnapshot()))
                .filter(row -> matchStatus(row, status))
                .filter(row -> matchKeyword(row, keyword))
                .sorted(Comparator
                        .comparing(GradingSubmissionRow::className,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(GradingSubmissionRow::studentName,
                                Comparator.nullsLast(String::compareTo)))
                .toList();
        return new ExportRows(context, config, rows);
    }

    private List<GradingSubmissionRow> loadRows(
            TaskContext context, GradingConfigView config) {
        List<UpstreamQueryPort.Assignment> assignments =
                upstream.findAssignments(context.task().id());
        List<UpstreamQueryPort.Submission> submissions =
                upstream.findSubmissionsByTask(context.task().id());
        Map<UUID, UpstreamQueryPort.Submission> submissionByStudent = new HashMap<>();
        for (UpstreamQueryPort.Submission submission : submissions) {
            submissionByStudent.putIfAbsent(submission.studentAccountId(), submission);
        }
        Map<UUID, UpstreamQueryPort.Session> latestSession = new HashMap<>();
        for (UpstreamQueryPort.Session session : upstream.findSessionsByTask(context.task().id())) {
            latestSession.putIfAbsent(session.studentAccountId(), session);
        }

        Set<UUID> submissionIds = new HashSet<>();
        assignments.forEach(assignment -> {
            UpstreamQueryPort.Submission submission =
                    submissionByStudent.get(assignment.studentAccountId());
            if (submission != null) {
                submissionIds.add(submission.id());
            }
        });
        Map<UUID, UpstreamQueryPort.Evaluation> evaluations =
                upstream.findEvaluationsBySubmissionIds(submissionIds);
        Map<UUID, TeacherScore> teacherScores =
                teacherScoreRepository.findBySubmissionIds(submissionIds);
        Map<UUID, FinalScore> finalScores =
                finalScoreRepository.findBySubmissionIds(submissionIds);

        Set<UUID> accountIds = new HashSet<>();
        assignments.forEach(assignment -> accountIds.add(assignment.studentAccountId()));
        Map<UUID, UpstreamQueryPort.Account> accounts = upstream.findAccounts(accountIds);
        Map<UUID, String> courseStatuses = upstream.findCourseStudentStatuses(
                context.course().id(), accountIds);

        Set<UUID> classIds = new HashSet<>();
        assignments.forEach(assignment -> addIfPresent(classIds, assignment.classIdSnapshot()));
        submissions.forEach(submission -> addIfPresent(classIds, submission.classIdSnapshot()));
        teacherScores.values().forEach(score -> addIfPresent(classIds, score.classIdSnapshot()));
        finalScores.values().forEach(score -> addIfPresent(classIds, score.classIdSnapshot()));
        accounts.values().forEach(account -> addIfPresent(classIds, account.classId()));
        Map<UUID, String> classNames = upstream.findClassNames(classIds);

        List<GradingSubmissionRow> rows = new ArrayList<>();
        for (UpstreamQueryPort.Assignment assignment : assignments) {
            UUID studentId = assignment.studentAccountId();
            UpstreamQueryPort.Account account = accounts.get(studentId);
            UpstreamQueryPort.Submission submission = submissionByStudent.get(studentId);
            UpstreamQueryPort.Session session = latestSession.get(studentId);
            UUID classSnapshot = effectiveClass(
                    assignment.classIdSnapshot(),
                    submission == null ? null : submission.classIdSnapshot(),
                    submission == null ? null : teacherScores.get(submission.id()),
                    submission == null ? null : finalScores.get(submission.id()),
                    account == null ? null : account.classId());

            AiScoreView ai = null;
            TeacherScoreRow teacherRow = null;
            FinalScoreRow finalRow = null;
            if (submission != null) {
                UpstreamQueryPort.Evaluation evaluation = evaluations.get(submission.id());
                ai = evaluation == null ? null : new AiScoreView(
                        evaluation.id(), evaluation.status(),
                        "completed".equals(evaluation.status())
                                ? evaluation.totalScore() : null,
                        evaluation.dimensionScores());
                TeacherScore teacher = teacherScores.get(submission.id());
                teacherRow = teacher == null ? null : toTeacherRow(teacher);
                FinalScore finalScore = finalScores.get(submission.id());
                // Always recalculate on read: the stored row is a cache, and this keeps the
                // 4-second polling current even if the terminal event is delayed. All inputs
                // are already loaded, so this does not add N+1 queries.
                AiEvaluationSnapshot aiSnapshot = evaluation == null ? null
                        : new AiEvaluationSnapshot(evaluation.id(), evaluation.status(),
                                evaluation.totalScore(), evaluation.dimensionScores(),
                                evaluation.errorCode(), evaluation.errorMessage());
                FinalScoreResult preview = finalScoreCalculator.calculate(
                        new FinalScoreInput(
                                config.aiWeight(), config.teacherWeight(),
                                ScoringMode.fromWire(config.scoringMode()),
                                context.rubric(), aiSnapshot, teacher, null, null));
                finalRow = new FinalScoreRow(
                        preview.status().wireValue(), preview.aiTotal(), preview.teacherTotal(),
                        preview.finalTotal(), preview.finalDimensions(),
                        preview.errorCode(), finalScore == null ? null : finalScore.computedAt());
            }

            rows.add(new GradingSubmissionRow(
                    submission == null ? null : submission.id(),
                    studentId,
                    account == null ? studentId.toString() : account.username(),
                    classSnapshot,
                    classSnapshot == null
                            ? "未分班" : classNames.getOrDefault(classSnapshot, "未分班"),
                    classSnapshot,
                    courseStatuses.getOrDefault(studentId, "active"),
                    submission != null ? "submitted" : sessionStatus(session),
                    submission == null ? null : submission.submittedAt(),
                    ai,
                    teacherRow,
                    finalRow));
        }
        return rows;
    }

    private SubmissionSummary summarize(List<GradingSubmissionRow> rows) {
        int assigned = rows.size();
        int submitted = 0;
        int completed = 0;
        int pendingAi = 0;
        int pendingTeacher = 0;
        int aiPartial = 0;
        int aiFailed = 0;
        int inProgress = 0;
        for (GradingSubmissionRow row : rows) {
            if (row.submissionId() != null) {
                submitted++;
            } else if ("preparing".equals(row.sessionStatus())
                    || "in_progress".equals(row.sessionStatus())) {
                inProgress++;
            }
            String status = row.finalScore() == null ? null : row.finalScore().status();
            if (status == null) {
                continue;
            }
            switch (status) {
                case "completed" -> completed++;
                case "pending_ai" -> pendingAi++;
                case "pending_teacher" -> pendingTeacher++;
                case "ai_partial" -> aiPartial++;
                case "ai_failed" -> aiFailed++;
                default -> {
                }
            }
        }
        return new SubmissionSummary(assigned, submitted, completed, pendingAi, pendingTeacher,
                aiPartial, aiFailed, assigned - submitted, inProgress);
    }

    private static boolean matchStatus(GradingSubmissionRow row, String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        if ("not_submitted".equals(status)) {
            return row.submissionId() == null;
        }
        if ("in_progress".equals(status)) {
            return row.submissionId() == null
                    && ("preparing".equals(row.sessionStatus())
                    || "in_progress".equals(row.sessionStatus()));
        }
        return row.finalScore() != null && status.equals(row.finalScore().status());
    }

    private static boolean matchKeyword(GradingSubmissionRow row, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        String name = row.studentName() == null ? "" : row.studentName().toLowerCase(Locale.ROOT);
        return name.contains(needle)
                || row.studentAccountId().toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static void addIfPresent(Set<UUID> set, UUID value) {
        if (value != null) {
            set.add(value);
        }
    }

    private static UUID effectiveClass(
            UUID assignmentClass,
            UUID submissionClass,
            TeacherScore teacher,
            FinalScore finalScore,
            UUID currentClass) {
        if (assignmentClass != null) {
            return assignmentClass;
        }
        if (submissionClass != null) {
            return submissionClass;
        }
        if (teacher != null && teacher.classIdSnapshot() != null) {
            return teacher.classIdSnapshot();
        }
        if (finalScore != null && finalScore.classIdSnapshot() != null) {
            return finalScore.classIdSnapshot();
        }
        return currentClass;
    }

    private static String sessionStatus(UpstreamQueryPort.Session session) {
        return session == null ? "not_started" : session.status();
    }

    private static TeacherScoreRow toTeacherRow(TeacherScore score) {
        return new TeacherScoreRow(
                score.id(), score.scoringMode().wireValue(), score.dimensionScores(),
                score.teacherTotal(), score.comment(), score.version(), score.gradedBy(),
                score.gradedAt());
    }

    /** Export payload carries the row set plus the task/config context for CSV headers. */
    public record ExportRows(
            TaskContext context, GradingConfigView config, List<GradingSubmissionRow> rows) {
    }

    /** Convenience for tests / callers that need the map keyed by student. */
    public Map<UUID, GradingSubmissionRow> byStudent(List<GradingSubmissionRow> rows) {
        Map<UUID, GradingSubmissionRow> map = new LinkedHashMap<>();
        rows.forEach(row -> map.put(row.studentAccountId(), row));
        return map;
    }
}
