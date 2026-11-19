package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.NOW;
import static com.shijiangtai.m3classroom.TestFixtures.admin;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.otherTeacher;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.MaterialUpload;
import com.shijiangtai.m3classroom.application.command.Patch;
import com.shijiangtai.m3classroom.application.command.UpdateTaskCommand;
import com.shijiangtai.m3classroom.application.port.AfterCommitPort;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
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
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskApplicationServiceTest {

    private InMemoryTaskRepository tasks;
    private InMemoryTaskAssignmentRepository assignments;
    private FakeCourseQueryPort courses;
    private FakeMaterialObjectStorage storage;
    private SessionRepository sessions;
    private SubmissionRepository submissions;
    private TaskApplicationService service;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        assignments = new InMemoryTaskAssignmentRepository();
        courses = com.shijiangtai.m3classroom.TestFixtures.coursePort();
        storage = new FakeMaterialObjectStorage();
        sessions = new InMemorySessionRepository();
        submissions = new InMemorySubmissionRepository();
        FakeQuestionBankQueryPort questionBank = new FakeQuestionBankQueryPort();
        RecordingEventPublisher events = new RecordingEventPublisher();
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
        TaskDraftFactory draftFactory = new TaskDraftFactory(UUID::randomUUID);
        ReferenceMaterialFactory materialFactory =
                new ReferenceMaterialFactory(extractor, storage, UUID::randomUUID);
        AfterCommitPort afterCommit = Runnable::run;
        TaskPublishService publishService = new TaskPublishServiceImpl(
                tasks, assignments, courses, questionBank, access,
                new TaskPublishPolicy(), new TaskSnapshotFactory(), draftFactory,
                materialFactory, sessions, submissions, UUID::randomUUID, () -> NOW,
                events, afterCommit);
        service = new TaskApplicationServiceImpl(
                tasks, assignments, courses, access, publishService, sessions, () -> NOW);
    }

    @Test
    void createFreePublishesWithDefaultsAndMaterial() {
        TaskView view = service.create(teacher(), freeCommandWithStudent(), upload());

        assertThat(view.mode()).isEqualTo("free");
        assertThat(view.durationRecommendedSeconds()).isEqualTo(900);
        assertThat(view.durationMaxSeconds()).isEqualTo(1200);
        assertThat(view.rubricWeights()).containsEntry("content", 30);
        assertThat(view.status()).isEqualTo("published");
        assertThat(view.configVersion()).isEqualTo(1);
        assertThat(view.referenceMaterial()).isNotNull();
        assertThat(assignments.findByTask(new TaskId(view.taskId()))).hasSize(2);
    }

    @Test
    void createScenarioKeepsScenario() {
        CreateTaskCommand command = new CreateTaskCommand(
                COURSE, com.shijiangtai.m3classroom.domain.task.TaskMode.SCENARIO,
                "课堂管理", "教材", null, null, null,
                new com.shijiangtai.m3classroom.domain.task.ScenarioConfig("班主任", "学生玩手机"),
                List.of(com.shijiangtai.m3classroom.TestFixtures.virtualStudentSpec()), null);
        TaskView view = service.create(teacher(), command, upload());
        assertThat(view.scenario()).isNotNull();
        assertThat(view.scenario().role()).isEqualTo("班主任");
    }

    @Test
    void createFreeWithoutMaterialFails() {
        assertCode(() -> service.create(teacher(), freeCommandWithStudent(), null),
                ErrorCode.REFERENCE_MATERIAL_MISSING);
        assertThat(tasks.findByCourse(COURSE)).isEmpty();
    }

    @Test
    void updatePublishedTaskReplacesMaterialAndAdvancesVersion() {
        TaskView created = service.create(teacher(), freeCommandWithStudent(), upload());
        TaskId id = new TaskId(created.taskId());

        TaskView updated = service.update(teacher(), id, topicUpdate("新课题"), null);

        assertThat(updated.topic()).isEqualTo("新课题");
        assertThat(updated.status()).isEqualTo("published");
        assertThat(updated.configVersion()).isEqualTo(2);
        assertThat(updated.referenceMaterial()).isNotNull();
    }

    @Test
    void otherTeacherCannotEdit() {
        TaskView created = service.create(teacher(), freeCommandWithStudent(), upload());
        assertCode(() -> service.update(
                otherTeacher(), new TaskId(created.taskId()), topicUpdate("新课题"), null),
                ErrorCode.TEACHER_NOT_IN_GROUP);
    }

    @Test
    void adminCannotEdit() {
        TaskView created = service.create(teacher(), freeCommandWithStudent(), upload());
        assertCode(() -> service.update(
                admin(), new TaskId(created.taskId()), topicUpdate("新课题"), null),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void softDeleteHidesTaskButKeepsRow() {
        TaskView created = service.create(teacher(), freeCommandWithStudent(), upload());
        TaskId id = new TaskId(created.taskId());

        service.softDelete(teacher(), id);

        assertThat(tasks.findById(id).orElseThrow().status()).isEqualTo(TaskStatus.DELETED);
        assertCode(() -> service.get(teacher(), id), ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(service.listCourseTasks(
                teacher(), COURSE,
                new com.shijiangtai.m3classroom.application.command.TaskQuery(
                        1, 20, null, null)).items()).isEmpty();
    }

    @Test
    void getReturnsPublishedTeacherView() {
        TaskView created = service.create(teacher(), freeCommandWithStudent(), upload());
        TaskView view = service.get(teacher(), new TaskId(created.taskId()));
        assertThat(view.topic()).isEqualTo("荷塘月色");
        assertThat(view.status()).isEqualTo("published");
        assertThat(view.rubricWeights()).containsEntry("content", 30);
    }

    private UpdateTaskCommand topicUpdate(String topic) {
        return new UpdateTaskCommand(
                Patch.absent(), Patch.of(topic), Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent());
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
