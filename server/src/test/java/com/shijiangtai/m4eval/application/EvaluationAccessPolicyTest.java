package com.shijiangtai.m4eval.application;

import static com.shijiangtai.m4eval.application.M4TestDoubles.COURSE_ID;
import static com.shijiangtai.m4eval.application.M4TestDoubles.NOW;
import static com.shijiangtai.m4eval.application.M4TestDoubles.OTHER_SCHOOL_ID;
import static com.shijiangtai.m4eval.application.M4TestDoubles.SCHOOL_ID;
import static com.shijiangtai.m4eval.application.M4TestDoubles.STUDENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m4eval.application.M4TestDoubles.StubInputQuery;
import com.shijiangtai.m4eval.domain.Evaluation;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Report permissions (M4-T09): owner, course teacher, admin 403, training private, cross-school. */
class EvaluationAccessPolicyTest {

    private static final UUID EVALUATION_ID =
            UUID.fromString("11111111-aaaa-aaaa-aaaa-111111111111");
    private static final UUID SUBMISSION_ID =
            UUID.fromString("33333333-cccc-cccc-cccc-333333333333");
    private static final UUID TRAINING_RECORD_ID =
            UUID.fromString("44444444-dddd-dddd-dddd-444444444444");
    private static final UUID TEACHER_ID =
            UUID.fromString("55555555-eeee-eeee-eeee-555555555555");
    private static final UUID OTHER_STUDENT_ID =
            UUID.fromString("66666666-ffff-ffff-ffff-666666666666");

    private StubInputQuery inputs;
    private EvaluationAccessPolicy policy;
    private Evaluation submissionEvaluation;
    private Evaluation trainingEvaluation;

    @BeforeEach
    void setUp() {
        inputs = new StubInputQuery();
        inputs.courseSchools.put(COURSE_ID, SCHOOL_ID);
        policy = new EvaluationAccessPolicy(inputs);
        submissionEvaluation = M4TestDoubles.pendingSubmission(EVALUATION_ID, SUBMISSION_ID);
        trainingEvaluation = M4TestDoubles.pendingTraining(
                EVALUATION_ID, TRAINING_RECORD_ID);
    }

    private static CurrentUser user(UUID accountId, UUID schoolId, AccountType type) {
        return new CurrentUser(
                new AccountId(accountId), new SchoolId(schoolId), type);
    }

    private static void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void studentOwnerMayReadSubmissionReport() {
        assertThatCode(() -> policy.requireRead(
                user(STUDENT_ID, SCHOOL_ID, AccountType.STUDENT), submissionEvaluation))
                .doesNotThrowAnyException();
    }

    @Test
    void otherStudentMayNotReadSubmissionReport() {
        assertForbidden(() -> policy.requireRead(
                user(OTHER_STUDENT_ID, SCHOOL_ID, AccountType.STUDENT),
                submissionEvaluation));
    }

    @Test
    void courseTeacherMayReadSubmissionReport() {
        inputs.teacherInGroup(COURSE_ID, TEACHER_ID);

        assertThatCode(() -> policy.requireRead(
                user(TEACHER_ID, SCHOOL_ID, AccountType.TEACHER), submissionEvaluation))
                .doesNotThrowAnyException();
    }

    @Test
    void teacherOutsideTheCourseGroupIsForbidden() {
        assertForbidden(() -> policy.requireRead(
                user(TEACHER_ID, SCHOOL_ID, AccountType.TEACHER), submissionEvaluation));
    }

    @Test
    void adminIsAlwaysForbidden() {
        assertForbidden(() -> policy.requireRead(
                user(OTHER_STUDENT_ID, SCHOOL_ID, AccountType.ADMIN),
                submissionEvaluation));
        assertForbidden(() -> policy.requireRead(
                user(OTHER_STUDENT_ID, SCHOOL_ID, AccountType.ADMIN),
                trainingEvaluation));
    }

    @Test
    void crossSchoolTeacherIsForbidden() {
        inputs.teacherInGroup(COURSE_ID, TEACHER_ID);

        assertForbidden(() -> policy.requireRead(
                user(TEACHER_ID, OTHER_SCHOOL_ID, AccountType.TEACHER),
                submissionEvaluation));
    }

    @Test
    void trainingReportIsOwnerOnly() {
        assertThatCode(() -> policy.requireRead(
                user(STUDENT_ID, SCHOOL_ID, AccountType.STUDENT), trainingEvaluation))
                .doesNotThrowAnyException();
        assertForbidden(() -> policy.requireRead(
                user(OTHER_STUDENT_ID, SCHOOL_ID, AccountType.STUDENT),
                trainingEvaluation));
        assertForbidden(() -> policy.requireRead(
                user(STUDENT_ID, SCHOOL_ID, AccountType.TEACHER), trainingEvaluation));
    }

    @Test
    void missingCourseSkipsTheSchoolCheck() {
        Evaluation evaluation = M4TestDoubles.pendingTrainingWithoutCourse(
                EVALUATION_ID, TRAINING_RECORD_ID);

        assertThatCode(() -> policy.requireRead(
                user(STUDENT_ID, OTHER_SCHOOL_ID, AccountType.STUDENT), evaluation))
                .doesNotThrowAnyException();
    }
}
