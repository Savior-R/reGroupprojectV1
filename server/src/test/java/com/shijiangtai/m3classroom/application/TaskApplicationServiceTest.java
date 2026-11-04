package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.NOW;
import static com.shijiangtai.m3classroom.TestFixtures.admin;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommand;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.interviewCommand;
import static com.shijiangtai.m3classroom.TestFixtures.material;
import static com.shijiangtai.m3classroom.TestFixtures.otherTeacher;
import static com.shijiangtai.m3classroom.TestFixtures.scenarioCommand;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.command.Patch;
import com.shijiangtai.m3classroom.application.command.UpdateTaskCommand;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.m3classroom.support.FakeMaterialObjectStorage;
import com.shijiangtai.m3classroom.support.InMemoryTaskAssignmentRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.TaskId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskApplicationServiceTest {

    private InMemoryTaskRepository tasks;
    private InMemoryTaskAssignmentRepository assignments;
    private FakeCourseQueryPort courses;
    private FakeMaterialObjectStorage storage;
    private TaskApplicationService service;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        assignments = new InMemoryTaskAssignmentRepository();
        courses = com.shijiangtai.m3classroom.TestFixtures.coursePort();
        storage = new FakeMaterialObjectStorage();
        service = new TaskApplicationServiceImpl(
                tasks, assignments, courses, new TaskAccessPolicy(courses),
                UUID::randomUUID, () -> NOW, storage, action -> action.run());
    }

    @Test
    void createFreeDraftAppliesDefaults() {
        TaskView view = service.createDraft(teacher(), freeCommand());

        assertThat(view.mode()).isEqualTo("free");
        assertThat(view.durationRecommendedSeconds()).isEqualTo(900);
        assertThat(view.durationMaxSeconds()).isEqualTo(1200);
        assertThat(view.rubricWeights()).containsEntry("content", 30);
        assertThat(view.status()).isEqualTo("draft");
    }

    @Test
    void createInterviewDraftDerivesDurations() {
        TaskView view = service.createDraft(teacher(), interviewCommand());

        assertThat(view.durationRecommendedSeconds()).isEqualTo(300);
        assertThat(view.durationMaxSeconds()).isEqualTo(300);
        assertThat(view.questionConfig()).isNotNull();
        assertThat(view.questionConfig().totalDurationSeconds()).isEqualTo(300);
        assertThat(view.rubricWeights()).containsOnlyKeys("content", "expression", "time");
    }

    @Test
    void createScenarioDraftKeepsScenario() {
        TaskView view = service.createDraft(teacher(), scenarioCommand());
        assertThat(view.scenario()).isNotNull();
        assertThat(view.scenario().role()).isEqualTo("班主任");
    }

    @Test
    void modeSwitchClearsIncompatibleConfigAndResetsWeights() {
        TaskView created = service.createDraft(teacher(), freeCommandWithStudent());
        Task task = tasks.findById(new TaskId(created.taskId())).orElseThrow();
        task.attachMaterial(material("old-key"), NOW);
        tasks.save(task);

        TaskView updated = service.updateDraft(
                teacher(), new TaskId(created.taskId()), modeUpdate(TaskMode.INTERVIEW));

        assertThat(updated.referenceMaterial()).isNull();
        assertThat(updated.virtualStudents()).isEmpty();
        assertThat(updated.rubricWeights()).containsOnlyKeys("content", "expression", "time");
        assertThat(updated.durationRecommendedSeconds()).isEqualTo(300);
        assertThat(updated.durationMaxSeconds()).isEqualTo(300);
        assertThat(updated.questionConfig()).isNotNull();
    }

    @Test
    void modeSwitchDeletesOldMaterialObjectAfterCommit() {
        TaskView created = service.createDraft(teacher(), freeCommandWithStudent());
        Task task = tasks.findById(new TaskId(created.taskId())).orElseThrow();
        storage.put("old-key", new byte[]{1, 2, 3}, "text/plain");
        task.attachMaterial(material("old-key"), NOW);
        tasks.save(task);

        service.updateDraft(teacher(), new TaskId(created.taskId()),
                modeUpdate(TaskMode.INTERVIEW));

        assertThat(storage.get("old-key")).isNull();
    }

    @Test
    void otherTeacherCannotEdit() {
        TaskView created = service.createDraft(teacher(), freeCommand());
        assertCode(() -> service.updateDraft(
                otherTeacher(), new TaskId(created.taskId()), topicUpdate("新课题")),
                ErrorCode.TEACHER_NOT_IN_GROUP);
    }

    @Test
    void adminCannotEdit() {
        TaskView created = service.createDraft(teacher(), freeCommand());
        assertCode(() -> service.updateDraft(
                admin(), new TaskId(created.taskId()), topicUpdate("新课题")),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void deleteOnlyDraft() {
        TaskView created = service.createDraft(teacher(), freeCommand());
        service.deleteDraft(teacher(), new TaskId(created.taskId()));
        assertThat(tasks.findById(new TaskId(created.taskId()))).isEmpty();
    }

    @Test
    void publishedTaskCannotBeUpdatedOrDeleted() {
        TaskView created = service.createDraft(teacher(), freeCommand());
        TaskId id = new TaskId(created.taskId());
        Task task = tasks.findById(id).orElseThrow();
        Task published = Task.rehydrate(
                task.id(), task.courseId(), task.createdBy(), task.draft(),
                TaskStatus.PUBLISHED, task.referenceMaterial(), null, 1,
                NOW, null, task.createdAt(), task.updatedAt());
        tasks.save(published);

        assertCode(() -> service.updateDraft(teacher(), id, topicUpdate("新课题")),
                ErrorCode.TASK_NOT_DRAFT);
        assertCode(() -> service.deleteDraft(teacher(), id), ErrorCode.TASK_NOT_DRAFT);
    }

    @Test
    void getReturnsTeacherView() {
        TaskView created = service.createDraft(teacher(), freeCommand());
        TaskView view = service.get(teacher(), new TaskId(created.taskId()));
        assertThat(view.topic()).isEqualTo("荷塘月色");
        assertThat(view.status()).isEqualTo("draft");
        assertThat(view.rubricWeights()).containsEntry("content", 30);
    }

    private UpdateTaskCommand modeUpdate(TaskMode mode) {
        return new UpdateTaskCommand(
                Patch.of(mode), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent());
    }

    private UpdateTaskCommand topicUpdate(String topic) {
        return new UpdateTaskCommand(
                Patch.absent(), Patch.of(topic), Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent());
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
