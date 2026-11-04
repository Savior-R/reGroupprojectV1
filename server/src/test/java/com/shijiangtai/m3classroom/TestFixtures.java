package com.shijiangtai.m3classroom;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.Patch;
import com.shijiangtai.m3classroom.application.command.UpdateTaskCommand;
import com.shijiangtai.m3classroom.application.command.VirtualStudentSpec;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.CourseStudentInfo;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.ReferenceMaterial;
import com.shijiangtai.m3classroom.domain.task.ScenarioConfig;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.RubricWeights;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.SchoolId;
import com.shijiangtai.shared.domain.TaskId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TestFixtures {
    public static final UUID SCHOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID COURSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");
    public static final UUID CLASS_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    public static final UUID TEACHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    public static final UUID OTHER_TEACHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    public static final UUID STUDENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    public static final UUID STUDENT2_ID = UUID.fromString("00000000-0000-0000-0000-000000000021");
    public static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    public static final SchoolId SCHOOL = new SchoolId(SCHOOL_ID);
    public static final CourseId COURSE = new CourseId(COURSE_ID);
    public static final ClassId CLASS = new ClassId(CLASS_ID);
    public static final AccountId TEACHER = new AccountId(TEACHER_ID);
    public static final AccountId OTHER_TEACHER = new AccountId(OTHER_TEACHER_ID);
    public static final AccountId STUDENT = new AccountId(STUDENT_ID);
    public static final AccountId STUDENT2 = new AccountId(STUDENT2_ID);

    private TestFixtures() {
    }

    public static CurrentUser teacher() {
        return new CurrentUser(TEACHER, SCHOOL, AccountType.TEACHER);
    }

    public static CurrentUser otherTeacher() {
        return new CurrentUser(OTHER_TEACHER, SCHOOL, AccountType.TEACHER);
    }

    public static CurrentUser admin() {
        return new CurrentUser(new AccountId(UUID.randomUUID()), SCHOOL, AccountType.ADMIN);
    }

    public static CurrentUser student() {
        return new CurrentUser(STUDENT, SCHOOL, AccountType.STUDENT);
    }

    public static CurrentUser student2() {
        return new CurrentUser(STUDENT2, SCHOOL, AccountType.STUDENT);
    }

    public static CourseInfo course() {
        return new CourseInfo(COURSE_ID, SCHOOL_ID, "语文教学论", true,
                Instant.parse("2027-06-30T00:00:00Z"), List.of(TEACHER_ID));
    }

    public static FakeCourseQueryPort coursePort() {
        FakeCourseQueryPort port = new FakeCourseQueryPort();
        port.addCourse(course());
        port.addStudent(COURSE, new CourseStudentInfo(STUDENT_ID, CLASS_ID));
        port.addStudent(COURSE, new CourseStudentInfo(STUDENT2_ID, CLASS_ID));
        return port;
    }

    public static CreateTaskCommand freeCommand() {
        return new CreateTaskCommand(COURSE, TaskMode.FREE, "荷塘月色", "部编版",
                null, null, null, null, List.of(), null);
    }

    public static CreateTaskCommand freeCommandWithStudent() {
        return new CreateTaskCommand(COURSE, TaskMode.FREE, "荷塘月色", "部编版",
                null, null, null, null, List.of(virtualStudentSpec()), null);
    }

    public static CreateTaskCommand scenarioCommand() {
        return new CreateTaskCommand(COURSE, TaskMode.SCENARIO, "课堂管理", "教材",
                null, null, null, new ScenarioConfig("班主任", "学生玩手机"),
                List.of(), null);
    }

    public static CreateTaskCommand interviewCommand() {
        return new CreateTaskCommand(COURSE, TaskMode.INTERVIEW, "结构化面试", null,
                null, null, null, null, List.of(),
                new QuestionConfig(List.of(), 300));
    }

    public static VirtualStudentSpec virtualStudentSpec() {
        return new VirtualStudentSpec(null, "林小雨", "avatar-01", "性格外向",
                new BigDecimal("0.60"), new BigDecimal("0.50"), new BigDecimal("0.70"),
                new BigDecimal("0.50"), new BigDecimal("0.40"), 1, 1);
    }

    public static ReferenceMaterial material(String objectKey) {
        return new ReferenceMaterial(UUID.randomUUID(), "荷塘月色.txt", objectKey,
                "text/plain", 1024, "荷塘月色课文内容", 8);
    }

    public static UpdateTaskCommand emptyUpdate() {
        return new UpdateTaskCommand(
                Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent());
    }

    public static TaskDraft freeDraft() {
        return new TaskDraft(TaskMode.FREE, "荷塘月色", "部编版", 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null);
    }

    public static TaskId taskId() {
        return new TaskId(UUID.randomUUID());
    }
}
