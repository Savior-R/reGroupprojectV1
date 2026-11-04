package com.shijiangtai.m3classroom.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.CourseStudentInfo;
import com.shijiangtai.m3classroom.domain.task.RubricWeights;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskAccessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final UUID SCHOOL_ID = UUID.randomUUID();
    private static final UUID OTHER_SCHOOL_ID = UUID.randomUUID();
    private static final UUID COURSE_ID = UUID.randomUUID();
    private static final UUID TEACHER_ID = UUID.randomUUID();
    private static final UUID OTHER_TEACHER_ID = UUID.randomUUID();
    private static final UUID STUDENT_ID = UUID.randomUUID();
    private static final UUID CLASS_ID = UUID.randomUUID();

    private FakeCourseQueryPort courses;
    private TaskAccessPolicy policy;

    @BeforeEach
    void setUp() {
        courses = new FakeCourseQueryPort();
        courses.addCourse(new CourseInfo(COURSE_ID, SCHOOL_ID, "语文教学论", true, null,
                List.of(TEACHER_ID)));
        courses.addStudent(courseId(), new CourseStudentInfo(STUDENT_ID, CLASS_ID));
        policy = new TaskAccessPolicy(courses);
    }

    @Test
    void studentCannotUseTeacherPolicy() {
        assertCode(() -> policy.requireTeacherCourse(student(STUDENT_ID, SCHOOL_ID), courseId()),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void adminCannotUseTeacherPolicy() {
        assertCode(() -> policy.requireTeacherCourse(admin(SCHOOL_ID), courseId()),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void otherTeacherIsRejected() {
        assertCode(() -> policy.requireTeacherCourse(
                teacher(OTHER_TEACHER_ID, SCHOOL_ID), courseId()),
                ErrorCode.TEACHER_NOT_IN_GROUP);
    }

    @Test
    void crossSchoolTeacherIsRejected() {
        assertCode(() -> policy.requireTeacherCourse(
                teacher(TEACHER_ID, OTHER_SCHOOL_ID), courseId()),
                ErrorCode.CROSS_SCHOOL_ACCESS);
    }

    @Test
    void missingCourseIsRejected() {
        assertCode(() -> policy.requireTeacherCourse(
                teacher(TEACHER_ID, SCHOOL_ID), new CourseId(UUID.randomUUID())),
                ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void teacherInGroupPasses() {
        CourseInfo course = policy.requireTeacherCourse(
                teacher(TEACHER_ID, SCHOOL_ID), courseId());
        assertThat(course.id()).isEqualTo(COURSE_ID);
    }

    @Test
    void requireTeacherTaskLoadsCourse() {
        CourseInfo course = policy.requireTeacherTask(
                teacher(TEACHER_ID, SCHOOL_ID), task());
        assertThat(course.id()).isEqualTo(COURSE_ID);
    }

    @Test
    void assignedStudentPasses() {
        CourseInfo course = courses.findCourse(courseId()).orElseThrow();
        TaskAssignment assignment = assignment(STUDENT_ID);
        assertThatCode(() -> policy.requireStudentAssigned(
                student(STUDENT_ID, SCHOOL_ID), course, assignment))
                .doesNotThrowAnyException();
    }

    @Test
    void missingAssignmentIsRejected() {
        CourseInfo course = courses.findCourse(courseId()).orElseThrow();
        assertCode(() -> policy.requireStudentAssigned(
                student(STUDENT_ID, SCHOOL_ID), course, null),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void assignmentForAnotherStudentIsRejected() {
        CourseInfo course = courses.findCourse(courseId()).orElseThrow();
        assertCode(() -> policy.requireStudentAssigned(
                student(STUDENT_ID, SCHOOL_ID), course, assignment(UUID.randomUUID())),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void studentCourseSchoolMismatchIsRejected() {
        CourseInfo course = courses.findCourse(courseId()).orElseThrow();
        assertCode(() -> policy.requireStudentAssigned(
                student(STUDENT_ID, OTHER_SCHOOL_ID), course, assignment(STUDENT_ID)),
                ErrorCode.CROSS_SCHOOL_ACCESS);
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(expected));
    }

    private CurrentUser teacher(UUID accountId, UUID schoolId) {
        return new CurrentUser(new AccountId(accountId), new SchoolId(schoolId), AccountType.TEACHER);
    }

    private CurrentUser student(UUID accountId, UUID schoolId) {
        return new CurrentUser(new AccountId(accountId), new SchoolId(schoolId), AccountType.STUDENT);
    }

    private CurrentUser admin(UUID schoolId) {
        return new CurrentUser(new AccountId(UUID.randomUUID()), new SchoolId(schoolId), AccountType.ADMIN);
    }

    private CourseId courseId() {
        return new CourseId(COURSE_ID);
    }

    private TaskAssignment assignment(UUID studentId) {
        return new TaskAssignment(UUID.randomUUID(), new TaskId(UUID.randomUUID()),
                new AccountId(studentId), new ClassId(CLASS_ID), NOW, AssignmentStatus.ASSIGNED);
    }

    private Task task() {
        return Task.createDraft(
                new TaskId(UUID.randomUUID()), courseId(), new AccountId(TEACHER_ID),
                new TaskDraft(TaskMode.FREE, "荷塘月色", "部编版", 900, 1200,
                        RubricWeights.defaults(TaskMode.FREE), null, List.of(), null),
                NOW);
    }
}
