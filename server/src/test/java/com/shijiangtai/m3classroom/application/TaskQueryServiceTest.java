package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.CLASS;
import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.NOW;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.MaterialUpload;
import com.shijiangtai.m3classroom.application.command.StudentTaskQuery;
import com.shijiangtai.m3classroom.application.command.TaskQuery;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import com.shijiangtai.m3classroom.application.view.StudentTaskDetailView;
import com.shijiangtai.m3classroom.application.view.StudentTaskView;
import com.shijiangtai.m3classroom.application.view.TaskAssignmentView;
import com.shijiangtai.m3classroom.application.view.TaskCardView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskPublishPolicy;
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
import com.shijiangtai.shared.domain.PageResult;
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskQueryServiceTest {

    private InMemoryTaskRepository tasks;
    private InMemoryTaskAssignmentRepository assignments;
    private TaskApplicationService service;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        assignments = new InMemoryTaskAssignmentRepository();
        FakeCourseQueryPort courses = com.shijiangtai.m3classroom.TestFixtures.coursePort();
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
        TaskAccessPolicy access = new TaskAccessPolicy(courses);
        TaskPublishService publishService = new TaskPublishServiceImpl(
                tasks, assignments, courses, new FakeQuestionBankQueryPort(), access,
                new TaskPublishPolicy(), new TaskSnapshotFactory(),
                new TaskDraftFactory(UUID::randomUUID),
                new ReferenceMaterialFactory(extractor, new FakeMaterialObjectStorage(),
                        UUID::randomUUID),
                new InMemorySessionRepository(), new InMemorySubmissionRepository(),
                UUID::randomUUID, () -> NOW, new RecordingEventPublisher(), Runnable::run);
        service = new TaskApplicationServiceImpl(
                tasks, assignments, courses, access, publishService,
                new InMemorySessionRepository(), () -> NOW);
    }

    @Test
    void courseTaskListFiltersByStatusAndModeAndPaginates() {
        service.create(teacher(), freeCommandWithStudent(), upload());
        service.create(teacher(), freeCommandWithStudent(), upload());

        PageResult<TaskCardView> free = service.listCourseTasks(
                teacher(), COURSE, new TaskQuery(1, 10, TaskStatus.PUBLISHED, TaskMode.FREE));
        assertThat(free.total()).isEqualTo(2);

        PageResult<TaskCardView> page = service.listCourseTasks(
                teacher(), COURSE, new TaskQuery(1, 1, TaskStatus.PUBLISHED, TaskMode.FREE));
        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void studentListContainsOnlyAssignedTasks() {
        TaskView assigned = publishedTask("已分配", true);
        publishedTask("未分配", false);

        PageResult<StudentTaskView> page = service.listStudentTasks(
                student(), new StudentTaskQuery(1, 10));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).taskId()).isEqualTo(assigned.taskId());
        assertThat(page.items().get(0).courseName()).isEqualTo("语文教学论");
    }

    @Test
    void studentDetailHidesPrivilegedFields() {
        TaskView task = publishedTask("已分配", true);

        StudentTaskDetailView view = service.getStudentTask(
                student(), new TaskId(task.taskId()));

        List<String> components = java.util.Arrays.stream(
                        StudentTaskDetailView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(components).doesNotContain(
                "rubricWeights", "referenceMaterial", "publishedQuestions", "questionConfig");
    }

    @Test
    void unassignedStudentGetsForbidden() {
        TaskView task = publishedTask("未分配", false);
        assertCode(() -> service.getStudentTask(
                student(), new TaskId(task.taskId())), ErrorCode.FORBIDDEN);
    }

    @Test
    void assignmentsListRequiresTeacherGroup() {
        TaskView task = publishedTask("已分配", true);
        PageResult<TaskAssignmentView> page = service.listAssignments(
                teacher(), new TaskId(task.taskId()), 1, 10);
        assertThat(page.total()).isEqualTo(2);

        assertCode(() -> service.listAssignments(
                com.shijiangtai.m3classroom.TestFixtures.otherTeacher(),
                new TaskId(task.taskId()), 1, 10),
                ErrorCode.TEACHER_NOT_IN_GROUP);
    }

    private TaskView publishedTask(String topic, boolean assign) {
        CreateTaskCommand command = new CreateTaskCommand(
                COURSE, TaskMode.FREE, topic, "部编版", null, null, null, null,
                List.of(com.shijiangtai.m3classroom.TestFixtures.virtualStudentSpec()), null);
        TaskView view = service.create(teacher(), command, upload());
        if (!assign) {
            assignments.deleteByTask(new TaskId(view.taskId()));
        }
        return view;
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
