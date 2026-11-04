package com.shijiangtai.m3classroom.persistence;

import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.COURSE_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.SCHOOL_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.TEACHER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.VirtualStudentSpec;
import com.shijiangtai.m3classroom.application.view.PublishResult;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    private TaskPublishService publishService;

    @Autowired
    private TaskMaterialService materialService;

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
    void publishPersistsAssignmentsAndSnapshot() {
        TaskId taskId = createFreeTask(new CourseId(COURSE_ID));

        PublishResult result = publishService.publish(TEACHER, taskId);

        assertThat(result.status()).isEqualTo("published");
        assertThat(result.assignedCount()).isEqualTo(2);
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
        TaskId taskId = createFreeTask(new CourseId(emptyCourseId));

        assertCode(() -> publishService.publish(TEACHER, taskId), ErrorCode.TASK_NO_STUDENTS);

        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.DRAFT);
        assertThat(assignmentRepository.findByTask(taskId)).isEmpty();
        assertThat(recorder.events()).isEmpty();
    }

    @Test
    void questionBankFailureRollsBack() {
        TaskId taskId = createInterviewTask();
        questionBankQueryPort.setAvailable(false);

        assertCode(() -> publishService.publish(TEACHER, taskId),
                ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.DRAFT);
        assertThat(assignmentRepository.findByTask(taskId)).isEmpty();
        assertThat(recorder.events()).isEmpty();
    }

    @Test
    void concurrentPublishHasOneWinner() throws Exception {
        TaskId taskId = createFreeTask(new CourseId(COURSE_ID));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PublishResult> first = executor.submit(() -> {
                start.await();
                return publishService.publish(TEACHER, taskId);
            });
            Future<PublishResult> second = executor.submit(() -> {
                start.await();
                return publishService.publish(TEACHER, taskId);
            });
            start.countDown();

            List<PublishResult> successes = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<PublishResult> future : List.of(first, second)) {
                try {
                    successes.add(future.get(15, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }
            assertThat(successes).hasSize(1);
            assertThat(successes.get(0).status()).isEqualTo("published");
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0)).isInstanceOfSatisfying(DomainException.class,
                    exception -> assertThat(exception.code())
                            .isEqualTo(ErrorCode.TASK_ALREADY_PUBLISHED));
            assertThat(assignmentRepository.findByTask(taskId)).hasSize(2);
            assertThat(taskRepository.findById(taskId).orElseThrow().status())
                    .isEqualTo(TaskStatus.PUBLISHED);
            assertThat(recorder.events()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void publishedEventArrivesAfterCommit() {
        TaskId taskId = createFreeTask(new CourseId(COURSE_ID));

        publishService.publish(TEACHER, taskId);

        assertThat(recorder.events()).hasSize(1);
        assertThat(recorder.events().get(0).taskId()).isEqualTo(taskId.value());
        assertThat(recorder.events().get(0).assignedCount()).isEqualTo(2);
    }

    private TaskId createFreeTask(CourseId courseId) {
        CreateTaskCommand command = new CreateTaskCommand(
                courseId, TaskMode.FREE, "荷塘月色", "部编版",
                null, null, null, null,
                List.of(virtualStudentSpec()), null);
        TaskView view = applicationService.createDraft(TEACHER, command);
        TaskId taskId = new TaskId(view.taskId());
        materialService.upload(
                TEACHER, taskId, "a.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于测试发布。"
                        .getBytes(StandardCharsets.UTF_8));
        return taskId;
    }

    private TaskId createInterviewTask() {
        QuestionId questionId = new QuestionId(UUID.randomUUID());
        CreateTaskCommand command = new CreateTaskCommand(
                new CourseId(COURSE_ID), TaskMode.INTERVIEW, "结构化面试", null,
                null, null, null, null, List.of(),
                new QuestionConfig(List.of(questionId), 300));
        TaskView view = applicationService.createDraft(TEACHER, command);
        return new TaskId(view.taskId());
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
