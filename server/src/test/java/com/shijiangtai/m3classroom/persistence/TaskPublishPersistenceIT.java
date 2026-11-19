package com.shijiangtai.m3classroom.persistence;

import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.COURSE_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.SCHOOL_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.TEACHER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.MaterialUpload;
import com.shijiangtai.m3classroom.application.command.VirtualStudentSpec;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.m3classroom.infrastructure.crossmodule.InMemoryCourseQueryAdapter;
import com.shijiangtai.m3classroom.infrastructure.crossmodule.InMemoryQuestionBankQueryAdapter;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.SchoolId;
import com.shijiangtai.shared.domain.TaskId;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(PublishedEventRecorder.class)
class TaskPublishPersistenceIT extends PostgresIntegrationTest {
    private static final CurrentUser TEACHER = new CurrentUser(
            new AccountId(TEACHER_ID), new SchoolId(SCHOOL_ID), AccountType.TEACHER);

    @Autowired
    private TaskApplicationService applicationService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskAssignmentRepository assignmentRepository;
    @Autowired
    private InMemoryCourseQueryAdapter courseQueryPort;
    @Autowired
    private InMemoryQuestionBankQueryAdapter questionBankQueryPort;
    @Autowired
    private PublishedEventRecorder recorder;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearRecorder() {
        recorder.clear();
    }

    @AfterEach
    void restoreQuestionBank() {
        questionBankQueryPort.setAvailable(true);
    }

    @Test
    void createPersistsPublishedTaskAssignmentsSnapshotAndEvent() {
        TaskView view = applicationService.create(
                TEACHER, freeCommand(new CourseId(COURSE_ID)), upload());

        TaskId taskId = new TaskId(view.taskId());
        assertThat(view.status()).isEqualTo("published");
        assertThat(view.configVersion()).isEqualTo(1);
        assertThat(assignmentRepository.findByTask(taskId)).hasSize(2);
        Task task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.PUBLISHED);
        assertThat(task.snapshot()).isNotNull();
        assertThat(recorder.events()).hasSize(1);
        assertThat(recorder.events().get(0).assignedCount()).isEqualTo(2);
    }

    @Test
    void zeroStudentsRollsBackTaskAndAssignments() {
        UUID emptyCourseId = UUID.randomUUID();
        String emptyCourseName = "空课程-" + emptyCourseId;
        jdbc.update("""
                insert into m2_course.courses (id, school_id, name, created_by)
                values (?, ?, ?, ?) on conflict do nothing
                """, emptyCourseId, SCHOOL_ID, emptyCourseName, TEACHER_ID);
        courseQueryPort.addCourse(new CourseInfo(
                emptyCourseId, SCHOOL_ID, emptyCourseName, true,
                Instant.parse("2027-06-30T00:00:00Z"), List.of(TEACHER_ID)));

        assertCode(() -> applicationService.create(
                TEACHER, freeCommand(new CourseId(emptyCourseId)), upload()),
                ErrorCode.TASK_NO_STUDENTS);

        assertThat(taskRepository.findByCourse(new CourseId(emptyCourseId))).isEmpty();
        assertThat(recorder.events()).isEmpty();
    }

    @Test
    void questionBankFailureRollsBackCreate() {
        questionBankQueryPort.setAvailable(false);
        QuestionId questionId = new QuestionId(UUID.randomUUID());

        assertCode(() -> applicationService.create(
                TEACHER, interviewCommand(questionId), null), ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(taskRepository.findByCourse(new CourseId(COURSE_ID))).isEmpty();
        assertThat(recorder.events()).isEmpty();
    }

    private CreateTaskCommand freeCommand(CourseId courseId) {
        return new CreateTaskCommand(
                courseId, TaskMode.FREE, "荷塘月色", "部编版",
                null, null, null, null, List.of(virtualStudentSpec()), null);
    }

    private CreateTaskCommand interviewCommand(QuestionId questionId) {
        return new CreateTaskCommand(
                new CourseId(COURSE_ID), TaskMode.INTERVIEW, "结构化面试", null,
                null, null, null, null, List.of(),
                new QuestionConfig(List.of(questionId), 300));
    }

    private MaterialUpload upload() {
        return new MaterialUpload(
                "a.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于测试发布。"
                        .getBytes(StandardCharsets.UTF_8));
    }

    private VirtualStudentSpec virtualStudentSpec() {
        return new VirtualStudentSpec(
                null, "林小雨", "avatar-01", "性格外向",
                new BigDecimal("0.60"), new BigDecimal("0.50"), new BigDecimal("0.70"),
                new BigDecimal("0.50"), new BigDecimal("0.40"), 1, 1);
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
