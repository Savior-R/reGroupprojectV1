package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.application.view.StudentResultDetailView;
import com.shijiangtai.m7teacher.application.view.StudentResultListView;
import com.shijiangtai.m7teacher.application.view.StudentResultRow;
import com.shijiangtai.m7teacher.domain.FinalScore;
import com.shijiangtai.m7teacher.domain.FinalScoreRepository;
import com.shijiangtai.m7teacher.domain.FinalScoreResult;
import com.shijiangtai.m7teacher.domain.FinalScoreStatus;
import com.shijiangtai.m7teacher.domain.M7ErrorCode;
import com.shijiangtai.m7teacher.domain.M7Exception;
import com.shijiangtai.m7teacher.domain.Rubric;
import com.shijiangtai.m7teacher.domain.TeacherScore;
import com.shijiangtai.m7teacher.domain.TeacherScoreRepository;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Student result list / detail (FR-M7-07 / 07 §11.6).
 *
 * <p>Only {@code completed} rows expose scores. Pending rows expose the status only; partial /
 * failed rows expose the failure reason. Admins are rejected by {@link M7AccessPolicy}.
 */
@Service
public class StudentResultQueryService {

    private final M7AccessPolicy accessPolicy;
    private final FinalScoreService finalScoreService;
    private final UpstreamQueryPort upstream;
    private final FinalScoreRepository finalScoreRepository;
    private final TeacherScoreRepository teacherScoreRepository;

    public StudentResultQueryService(
            M7AccessPolicy accessPolicy,
            FinalScoreService finalScoreService,
            UpstreamQueryPort upstream,
            FinalScoreRepository finalScoreRepository,
            TeacherScoreRepository teacherScoreRepository) {
        this.accessPolicy = accessPolicy;
        this.finalScoreService = finalScoreService;
        this.upstream = upstream;
        this.finalScoreRepository = finalScoreRepository;
        this.teacherScoreRepository = teacherScoreRepository;
    }

    public StudentResultListView list(CurrentUser user, int page, int size) {
        if (user.type() == AccountType.ADMIN) {
            throw new M7Exception(M7ErrorCode.FORBIDDEN, "管理员不能访问 M7");
        }
        if (user.type() != AccountType.STUDENT) {
            throw new M7Exception(M7ErrorCode.FORBIDDEN, "只有学生可以查看本人成绩列表");
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);

        List<UpstreamQueryPort.Submission> submissions =
                upstream.findSubmissionsByStudent(user.accountId().value()).stream()
                        .sorted(Comparator.comparing(UpstreamQueryPort.Submission::submittedAt)
                                .reversed())
                        .toList();
        List<StudentResultRow> rows = new ArrayList<>();
        for (UpstreamQueryPort.Submission submission : submissions) {
            UpstreamQueryPort.Task task = upstream.findTask(submission.taskId()).orElse(null);
            if (task == null) {
                continue;
            }
            UpstreamQueryPort.Course course = upstream.findCourse(task.courseId()).orElse(null);
            FinalScore finalScore = finalScoreRepository.findBySubmissionId(submission.id())
                    .orElse(null);
            String status;
            if (finalScore != null) {
                status = finalScore.status().wireValue();
            } else if (course != null) {
                try {
                    TaskContext context = new TaskContext(
                            course, task, Rubric.fromSnapshot(task.mode(), task.rubricWeights()));
                    status = finalScoreService.preview(
                            new SubmissionAccess(submission, context, false))
                            .status().wireValue();
                } catch (M7Exception exception) {
                    status = FinalScoreStatus.AI_FAILED.wireValue();
                }
            } else {
                status = FinalScoreStatus.PENDING_AI.wireValue();
            }
            rows.add(new StudentResultRow(
                    submission.id(),
                    task.id(),
                    task.topic(),
                    course == null ? "" : course.name(),
                    submission.submittedAt(),
                    status,
                    finalScore != null && finalScore.status() == FinalScoreStatus.COMPLETED
                            ? finalScore.finalTotal() : null));
        }
        int from = Math.min((safePage - 1) * safeSize, rows.size());
        int to = Math.min(from + safeSize, rows.size());
        return new StudentResultListView(List.copyOf(rows.subList(from, to)), rows.size(),
                safePage, safeSize);
    }

    public StudentResultDetailView detail(CurrentUser user, UUID submissionId) {
        SubmissionAccess access = accessPolicy.requireSubmissionViewer(user, submissionId);
        UpstreamQueryPort.Submission submission = access.submission();
        TaskContext context = access.taskContext();
        FinalScoreResult result = finalScoreService.preview(access);
        TeacherScore teacher = teacherScoreRepository.findBySubmissionId(submissionId)
                .orElse(null);
        UpstreamQueryPort.Evaluation evaluation = upstream
                .findEvaluationBySubmission(submissionId).orElse(null);

        boolean completed = result.status() == FinalScoreStatus.COMPLETED;
        boolean pendingTeacher = result.status() == FinalScoreStatus.PENDING_TEACHER;
        boolean failed = result.status() == FinalScoreStatus.AI_FAILED
                || result.status() == FinalScoreStatus.AI_PARTIAL;

        return new StudentResultDetailView(
                submission.id(),
                context.task().id(),
                context.task().topic(),
                context.course().name(),
                submission.submittedAt(),
                result.status().wireValue(),
                completed || pendingTeacher ? result.aiTotal() : null,
                completed ? result.teacherTotal() : null,
                completed ? result.finalTotal() : null,
                completed ? result.finalDimensions() : Map.of(),
                completed && teacher != null ? teacher.comment() : null,
                evaluation == null
                        ? null : "/api/v1/evaluations/" + evaluation.id() + "/report",
                "/api/v1/sessions/" + submission.sessionId() + "/replay",
                completed ? null : result.errorCode(),
                completed ? null : result.errorMessage());
    }
}
