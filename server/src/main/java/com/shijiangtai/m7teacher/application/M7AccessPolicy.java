package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.domain.M7ErrorCode;
import com.shijiangtai.m7teacher.domain.M7Exception;
import com.shijiangtai.m7teacher.domain.Rubric;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resource-level authorization for M7 (FR-M7-08 / 07 §12).
 *
 * <p>Admins never enter M7, students only see their own submission, and teachers must be an
 * active member of the course teacher group in the same school. Cross-school access is denied
 * explicitly rather than being folded into a generic 403.
 */
@Component
public class M7AccessPolicy {

    private final UpstreamQueryPort upstream;

    public M7AccessPolicy(UpstreamQueryPort upstream) {
        this.upstream = upstream;
    }

    public TaskContext requireTeacherTask(CurrentUser user, UUID taskId) {
        requireTeacher(user);
        UpstreamQueryPort.Task task = upstream.findTask(taskId)
                .orElseThrow(() -> notFound("任务不存在", "task_id", taskId));
        UpstreamQueryPort.Course course = requireCourse(task.courseId());
        requireSameSchool(user, course);
        requireTeacherGroup(user, course);
        return new TaskContext(course, task, Rubric.fromSnapshot(task.mode(), task.rubricWeights()));
    }

    public TaskContext requireTeacherCourse(CurrentUser user, UUID courseId) {
        requireTeacher(user);
        UpstreamQueryPort.Course course = requireCourse(courseId);
        requireSameSchool(user, course);
        requireTeacherGroup(user, course);
        return new TaskContext(course, null, null);
    }

    public SubmissionAccess requireSubmissionViewer(CurrentUser user, UUID submissionId) {
        UpstreamQueryPort.Submission submission = upstream.findSubmission(submissionId)
                .orElseThrow(() -> notFound("提交件不存在", "submission_id", submissionId));
        UpstreamQueryPort.Task task = upstream.findTask(submission.taskId())
                .orElseThrow(() -> notFound("任务不存在", "task_id", submission.taskId()));
        UpstreamQueryPort.Course course = requireCourse(task.courseId());
        requireSameSchool(user, course);
        Rubric rubric = Rubric.fromSnapshot(task.mode(), task.rubricWeights());
        TaskContext context = new TaskContext(course, task, rubric);

        if (user.type() == AccountType.TEACHER) {
            requireTeacherGroup(user, course);
            return new SubmissionAccess(submission, context, true);
        }
        if (user.type() == AccountType.STUDENT) {
            if (!submission.studentAccountId().equals(user.accountId().value())) {
                throw new M7Exception(M7ErrorCode.FORBIDDEN, "不能查看他人成绩");
            }
            return new SubmissionAccess(submission, context, false);
        }
        throw new M7Exception(M7ErrorCode.FORBIDDEN, "管理员不能访问 M7");
    }

    public SubmissionAccess requireTeacherSubmission(CurrentUser user, UUID submissionId) {
        SubmissionAccess access = requireSubmissionViewer(user, submissionId);
        if (!access.teacherViewer()) {
            throw new M7Exception(M7ErrorCode.FORBIDDEN, "只有课程老师组可以评分");
        }
        return access;
    }

    public void requireNotArchived(TaskContext context) {
        if (context.course().archived()) {
            throw new M7Exception(M7ErrorCode.COURSE_ARCHIVED, "课程已归档，M7 只读",
                    Map.of("course_id", context.course().id()));
        }
    }

    public void requirePublished(TaskContext context) {
        if ("draft".equals(context.task().status())) {
            throw new M7Exception(M7ErrorCode.TASK_NOT_DRAFT, "草稿任务没有评分名单",
                    Map.of("task_id", context.task().id()));
        }
    }

    private void requireTeacher(CurrentUser user) {
        if (user.type() == AccountType.ADMIN) {
            throw new M7Exception(M7ErrorCode.FORBIDDEN, "管理员不能访问 M7");
        }
        if (user.type() != AccountType.TEACHER) {
            throw new M7Exception(M7ErrorCode.FORBIDDEN, "需要教师身份");
        }
    }

    private UpstreamQueryPort.Course requireCourse(UUID courseId) {
        return upstream.findCourse(courseId)
                .orElseThrow(() -> notFound("课程不存在", "course_id", courseId));
    }

    private void requireSameSchool(CurrentUser user, UpstreamQueryPort.Course course) {
        if (!course.schoolId().equals(user.schoolId().value())) {
            throw new M7Exception(M7ErrorCode.CROSS_SCHOOL_ACCESS, "跨校访问",
                    Map.of("course_id", course.id()));
        }
    }

    private void requireTeacherGroup(CurrentUser user, UpstreamQueryPort.Course course) {
        if (!upstream.isTeacherInGroup(course.id(), user.accountId().value())) {
            throw new M7Exception(M7ErrorCode.TEACHER_NOT_IN_GROUP, "不在课程老师组",
                    Map.of("course_id", course.id()));
        }
    }

    private static M7Exception notFound(String message, String field, Object value) {
        return new M7Exception(M7ErrorCode.RESOURCE_NOT_FOUND, message,
                Map.of(field, value.toString()));
    }
}
