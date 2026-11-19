package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.NOW;
import static com.shijiangtai.m3classroom.TestFixtures.SCHOOL_ID;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.MaterialUpload;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.m3classroom.domain.task.PublishQuestion;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskPublishPolicy;
import com.shijiangtai.m3classroom.domain.task.TaskPublishedEvent;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.m3classroom.support.FakeMaterialObjectStorage;
import com.shijiangtai.m3classroom.support.FakeQuestionBankQueryPort;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.m3classroom.support.InMemorySubmissionRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskAssignmentRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.m3classroom.support.RecordingEventPublisher;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
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
        publishService = publishService(courses);
        applicationService = new TaskApplicationServiceImpl(
                tasks, assignments, courses, new TaskAccessPolicy(courses),
                publishService, new InMemorySessionRepository(), () -> NOW);
    }

    @Test
    void createFreeTaskPublishesAssignmentsSnapshotAndEvent() {
        TaskView view = applicationService.create(
                teacher(), freeCommandWithStudent(), upload());

        TaskId taskId = new TaskId(view.taskId());
        assertThat(view.status()).isEqualTo("published");
        assertThat(view.configVersion()).isEqualTo(1);
        assertThat(assignments.findByTask(taskId)).hasSize(2);
        assertThat(tasks.findById(taskId).orElseThrow().snapshot()).isNotNull();
        assertThat(events.events()).hasSize(1);
        assertThat(events.events().get(0)).isInstanceOf(TaskPublishedEvent.class);
    }

    @Test
    void zeroStudentsFailsAndLeavesNoTask() {
        FakeCourseQueryPort empty = new FakeCourseQueryPort();
        empty.addCourse(com.shijiangtai.m3classroom.TestFixtures.course());
        TaskPublishService emptyService = publishService(empty);
        TaskApplicationService emptyApplication = new TaskApplicationServiceImpl(
                tasks, assignments, empty, new TaskAccessPolicy(empty),
                emptyService, new InMemorySessionRepository(), () -> NOW);

        assertCode(() -> emptyApplication.create(
                teacher(), freeCommandWithStudent(), upload()), ErrorCode.TASK_NO_STUDENTS);

        assertThat(tasks.findByCourse(COURSE)).isEmpty();
        assertThat(assignments.findByTask(new TaskId(UUID.randomUUID()))).isEmpty();
        assertThat(events.events()).isEmpty();
    }

    @Test
    void questionBankFailureLeavesNoTask() {
        questionBank.setAvailable(false);
        QuestionId questionId = new QuestionId(UUID.randomUUID());
        CreateTaskCommand command = interviewCommand(questionId);

        assertCode(() -> applicationService.create(teacher(), command, null),
                ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(tasks.findByCourse(COURSE)).isEmpty();
        assertThat(events.events()).isEmpty();
    }

    @Test
    void disabledQuestionLeavesNoTask() {
        QuestionId questionId = new QuestionId(UUID.randomUUID());
        questionBank.addQuestion(new PublishQuestion(
                questionId.value(), "题干", "答案要点", UUID.randomUUID(),
                "教育理念", SCHOOL_ID, false));

        assertCode(() -> applicationService.create(
                teacher(), interviewCommand(questionId), null),
                ErrorCode.QUESTION_DISABLED);

        assertThat(tasks.findByCourse(COURSE)).isEmpty();
    }

    @Test
    void closePublishedTaskAndRejectSecondClose() {
        TaskView view = applicationService.create(
                teacher(), freeCommandWithStudent(), upload());
        TaskId taskId = new TaskId(view.taskId());

        applicationService.close(teacher(), taskId);
        assertThat(tasks.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.CLOSED);
        assertCode(() -> applicationService.close(teacher(), taskId), ErrorCode.TASK_CLOSED);
    }

    private TaskPublishService publishService(FakeCourseQueryPort coursePort) {
        MaterialTextExtractor extractor = new MaterialTextExtractor() {
            @Override
            public boolean supports(String filename) {
                return true;
            }

            @Override
            public String extract(byte[] content, String filename, String mime) {
                return new String(content, StandardCharsets.UTF_8);
            }
        };
        TaskAccessPolicy access = new TaskAccessPolicy(coursePort);
        return new TaskPublishServiceImpl(
                tasks, assignments, coursePort, questionBank, access,
                new TaskPublishPolicy(), new TaskSnapshotFactory(),
                new TaskDraftFactory(UUID::randomUUID),
                new ReferenceMaterialFactory(extractor, new FakeMaterialObjectStorage(),
                        UUID::randomUUID),
                new InMemorySessionRepository(), new InMemorySubmissionRepository(),
                UUID::randomUUID, () -> NOW, events, Runnable::run);
    }

    private CreateTaskCommand interviewCommand(QuestionId questionId) {
        return new CreateTaskCommand(
                COURSE, TaskMode.INTERVIEW, "结构化面试", null, null, null,
                null, null, List.of(), new QuestionConfig(List.of(questionId), 300));
    }

    private MaterialUpload upload() {
        return new MaterialUpload(
                "荷塘月色.txt", "text/plain",
                "荷塘月色课文内容，用于任务参考材料解析。".getBytes(StandardCharsets.UTF_8));
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
