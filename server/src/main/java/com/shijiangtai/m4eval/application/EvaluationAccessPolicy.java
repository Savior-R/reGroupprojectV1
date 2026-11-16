package com.shijiangtai.m4eval.application;

import com.shijiangtai.m4eval.application.port.EvaluationInputQuery;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Report access rules (06 §10).
 *
 * <ul>
 *   <li>task submission: the student owner, or an active teacher of the course group;
 *   <li>training record: the student owner only — teachers and admins are forbidden;
 *   <li>admins are always forbidden on evaluation reports.
 * </ul>
 */
@Component
public class EvaluationAccessPolicy {

    private final EvaluationInputQuery inputQuery;

    public EvaluationAccessPolicy(EvaluationInputQuery inputQuery) {
        this.inputQuery = Objects.requireNonNull(inputQuery, "inputQuery");
    }

    public void requireRead(CurrentUser user, Evaluation evaluation) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(evaluation, "evaluation");
        if (user.type() == AccountType.ADMIN) {
            throw forbidden("无权限查看评测报告");
        }
        requireSameSchool(user, evaluation);

        boolean owner = evaluation.studentAccountId().equals(user.accountId().value());
        if (Evaluation.SOURCE_TRAINING_RECORD.equals(evaluation.sourceType())) {
            if (!owner || user.type() != AccountType.STUDENT) {
                throw forbidden("训练报告仅本人可见");
            }
            return;
        }

        if (user.type() == AccountType.STUDENT) {
            if (!owner) {
                throw forbidden("评测不属于当前学生");
            }
            return;
        }
        if (user.type() != AccountType.TEACHER) {
            throw forbidden("无权限查看评测报告");
        }
        if (evaluation.courseId() == null
                || !inputQuery.isTeacherInGroup(
                        evaluation.courseId(), user.accountId().value())) {
            throw forbidden("无权限查看评测报告");
        }
    }

    private void requireSameSchool(CurrentUser user, Evaluation evaluation) {
        if (evaluation.courseId() == null) {
            return;
        }
        inputQuery.findCourseSchoolId(evaluation.courseId()).ifPresent(schoolId -> {
            if (!schoolId.equals(user.schoolId().value())) {
                throw forbidden("跨校访问评测");
            }
        });
    }

    private static DomainException forbidden(String message) {
        return new DomainException(ErrorCode.FORBIDDEN, message);
    }
}
