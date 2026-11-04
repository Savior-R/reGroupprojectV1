package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.NOW;
import static com.shijiangtai.m3classroom.TestFixtures.SCHOOL_ID;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.material;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.view.PublishResult;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.task.PublishQuestion;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskPublishedEvent;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.m3classroom.support.FakeMaterialObjectStorage;
import com.shijiangtai.m3classroom.support.FakeQuestionBankQueryPort;
import com.shijiangtai.m3classroom.support.InMemoryTaskAssignmentRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.m3classroom.support.RecordingEventPublisher;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskPublishServiceTest {

    private InMemoryTaskRepository tasks;
    private InMemoryTaskAssignmentRepository assignments;
    private FakeCourseQueryPort courses;
    private FakeQuestionBankQueryPort questionBank;
    private RecordingEventPublisher events;
    private TaskApplicationService applicationService;
    private TaskPublishService publishService;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        assignments = new InMemoryTaskAssignmentRepository();
        courses = com.shijiangtai.m3classroom.TestFixtures.coursePort();
        questionBank = new FakeQuestionBankQueryPort();
        events = new RecordingEventPublisher();

        TaskAccessPolicy access = new TaskAccessPolicy(courses);
        applicationService = new TaskApplicationServiceImpl(
                tasks, assignments, courses, access, UUID::randomUUID, () -> NOW,
                new FakeMaterialObjectStorage(), action -> action.run());
        publishService = publishService(courses, access);
    }

    @Test
    void publishCreatesAssignmentsSnapshotAndEvent() {
        TaskId taskId = createFreeTask();

        PublishResult result = publishService.publish(teacher(), taskId);

        assertThat(result.status()).isEqualTo("published");
        assertThat(result.assignedCount()).isEqualTo(2);
        assertThat(assignments.findByTask(taskId)).hasSize(2);
        Task task = tasks.findById(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.PUBLISHED);
        assertThat(task.configVersion()).isEqualTo(1);
        assertThat(task.snapshot()).isNotNull();
        assertThat(events.events()).hasSize(1);
        assertThat(events.events().get(0)).isInstanceOf(TaskPublishedEvent.class);
    }

    @Test
    void zeroStudentsLeavesDraftAndNoAssignments() {
        TaskId taskId = createFreeTask();
        FakeCourseQueryPort empty = new FakeCourseQueryPort();
        empty.addCourse(com.shijiangtai.m3classroom.TestFixtures.course());
        TaskPublishService emptyService = publishService(empty, new TaskAccessPolicy(empty));

        assertCode(() -> emptyService.publish(teacher(), taskId), ErrorCode.TASK_NO_STUDENTS);

        Task task = tasks.findById(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.DRAFT);
        assertThat(assignments.findByTask(taskId)).isEmpty();
        assertThat(events.events()).isEmpty();
    }

    @Test
    void questionBankFailureLeavesDraftAndNoAssignments() {
        TaskId taskId = createInterviewTask();
        questionBank.setAvailable(false);

        assertCode(() -> publishService.publish(teacher(), taskId), ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(tasks.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.DRAFT);
        assertThat(assignments.findByTask(taskId)).isEmpty();
    }

    @Test
    void disabledQuestionLeavesDraft() {
        TaskId taskId = createInterviewTask();
        QuestionId questionId = tasks.findById(taskId).orElseThrow()
                .draft().questionConfig().questionIds().get(0);
        questionBank.addQuestion(new PublishQuestion(
                questionId.value(), "题干", "答案要点", UUID.randomUUID(),
                "教育理念", SCHOOL_ID, false));

        assertCode(() -> publishService.publish(teacher(), taskId), ErrorCode.QUESTION_DISABLED);

        assertThat(tasks.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.DRAFT);
        assertThat(assignments.findByTask(taskId)).isEmpty();
    }

    @Test
    void concurrentPublishHasOneWinner() throws Exception {
        TaskId taskId = createFreeTask();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PublishResult> first = executor.submit(() -> {
                start.await();
                return publishService.publish(teacher(), taskId);
            });
            Future<?> second = executor.submit(() -> {
                start.await();
                return publishService.publish(teacher(), taskId);
            });
            start.countDown();

            PublishResult winner = first.get(10, TimeUnit.SECONDS);
            assertThat(winner.status()).isEqualTo("published");
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(DomainException.class)
                    .satisfies(ex -> assertThat(((DomainException) ex.getCause()).code())
                            .isEqualTo(ErrorCode.TASK_ALREADY_PUBLISHED));
            assertThat(assignments.findByTask(taskId)).hasSize(2);
            assertThat(events.events()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closePublishedTask() {
        TaskId taskId = createFreeTask();
        publishService.publish(teacher(), taskId);
        publishService.close(teacher(), taskId);

        Task task = tasks.findById(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.CLOSED);
        assertThat(task.closedAt()).isEqualTo(NOW);
    }

    @Test
    void closeDraftRejected() {
        TaskId taskId = createFreeTask();
        assertCode(() -> publishService.close(teacher(), taskId), ErrorCode.TASK_NOT_DRAFT);
    }

    @Test
    void closeTwiceReturnsTaskClosed() {
        TaskId taskId = createFreeTask();
        publishService.publish(teacher(), taskId);
        publishService.close(teacher(), taskId);
        assertCode(() -> publishService.close(teacher(), taskId), ErrorCode.TASK_CLOSED);
    }

    private TaskPublishService publishService(FakeCourseQueryPort coursePort, TaskAccessPolicy access) {
        return new TaskPublishServiceImpl(
                tasks, assignments, coursePort, questionBank, access,
                new com.shijiangtai.m3classroom.domain.task.TaskPublishPolicy(),
                new com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory(),
                UUID::randomUUID, () -> NOW, events);
    }

    private TaskId createFreeTask() {
        TaskView view = applicationService.createDraft(teacher(), freeCommandWithStudent());
        TaskId taskId = new TaskId(view.taskId());
        Task task = tasks.findById(taskId).orElseThrow();
        task.attachMaterial(material("key-" + taskId.value()), NOW);
        tasks.save(task);
        return taskId;
    }

    private TaskId createInterviewTask() {
        QuestionId questionId = new QuestionId(UUID.randomUUID());
        CreateTaskCommand command = new CreateTaskCommand(
                COURSE, TaskMode.INTERVIEW, "结构化面试", null, null, null, null, null,
                List.of(), new QuestionConfig(List.of(questionId), 300));
        TaskView view = applicationService.createDraft(teacher(), command);
        return new TaskId(view.taskId());
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
