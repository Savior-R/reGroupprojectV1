package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.CLASS;
import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.NOW;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommand;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.interviewCommand;
import static com.shijiangtai.m3classroom.TestFixtures.material;
import static com.shijiangtai.m3classroom.TestFixtures.otherTeacher;
import static com.shijiangtai.m3classroom.TestFixtures.scenarioCommand;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.command.StudentTaskQuery;
import com.shijiangtai.m3classroom.application.command.TaskQuery;
import com.shijiangtai.m3classroom.application.view.StudentTaskDetailView;
import com.shijiangtai.m3classroom.application.view.StudentTaskView;
import com.shijiangtai.m3classroom.application.view.TaskAssignmentView;
import com.shijiangtai.m3classroom.application.view.TaskCardView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.m3classroom.support.FakeMaterialObjectStorage;
import com.shijiangtai.m3classroom.support.InMemoryTaskAssignmentRepository;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.PageResult;
import com.shijiangtai.shared.domain.TaskId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskQueryServiceTest {

    private InMemoryTaskRepository tasks;
    private InMemoryTaskAssignmentRepository assignments;
    private FakeCourseQueryPort courses;
    private TaskApplicationService service;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        assignments = new InMemoryTaskAssignmentRepository();
        courses = com.shijiangtai.m3classroom.TestFixtures.coursePort();
        service = new TaskApplicationServiceImpl(
                tasks, assignments, courses, new TaskAccessPolicy(courses),
                UUID::randomUUID, () -> NOW, new FakeMaterialObjectStorage(), action -> action.run());
    }

    @Test
    void courseTaskListFiltersByStatusAndModeAndPaginates() {
        service.createDraft(teacher(), freeCommand());
        service.createDraft(teacher(), freeCommand());
        service.createDraft(teacher(), scenarioCommand());
        service.createDraft(teacher(), interviewCommand());

        PageResult<TaskCardView> freeDrafts = service.listCourseTasks(
                teacher(), COURSE, new TaskQuery(1, 10, TaskStatus.DRAFT, TaskMode.FREE));
        assertThat(freeDrafts.total()).isEqualTo(2);

        PageResult<TaskCardView> page = service.listCourseTasks(
                teacher(), COURSE, new TaskQuery(1, 1, TaskStatus.DRAFT, TaskMode.FREE));
        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void studentListContainsOnlyAssignedTasks() {
        Task assigned = publishedTask("已分配", true);
        publishedTask("未分配", false);

        PageResult<StudentTaskView> page = service.listStudentTasks(
                student(), new StudentTaskQuery(1, 10));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).taskId()).isEqualTo(assigned.id().value());
        assertThat(page.items().get(0).courseName()).isEqualTo("语文教学论");
    }

    @Test
    void studentDetailHidesPrivilegedFields() {
        Task task = publishedTask("已分配", true);

        StudentTaskDetailView view = service.getStudentTask(student(), task.id());

        List<String> components = Arrays.stream(StudentTaskDetailView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(components).doesNotContain(
                "rubricWeights", "referenceMaterial", "publishedQuestions", "questionConfig");
        assertThat(view.toString()).doesNotContain("content=30", "answer", "material");
    }

    @Test
    void unassignedStudentGetsForbidden() {
        Task task = publishedTask("未分配", false);
        assertCode(() -> service.getStudentTask(student(), task.id()), ErrorCode.FORBIDDEN);
    }

    @Test
    void assignmentsListRequiresTeacherGroup() {
        Task task = publishedTask("已分配", true);
        PageResult<TaskAssignmentView> page = service.listAssignments(
                teacher(), task.id(), 1, 10);
        assertThat(page.total()).isEqualTo(1);

        assertCode(() -> service.listAssignments(otherTeacher(), task.id(), 1, 10),
                ErrorCode.TEACHER_NOT_IN_GROUP);
    }

    private Task publishedTask(String topic, boolean assign) {
        TaskView created = service.createDraft(teacher(),
                new com.shijiangtai.m3classroom.application.command.CreateTaskCommand(
                        COURSE, com.shijiangtai.m3classroom.domain.task.TaskMode.FREE,
                        topic, "部编版", null, null, null, null,
                        List.of(com.shijiangtai.m3classroom.TestFixtures.virtualStudentSpec()), null));
        TaskId id = new TaskId(created.taskId());
        Task draft = tasks.findById(id).orElseThrow();
        draft.attachMaterial(material("key-" + topic), NOW);
        Task published = Task.rehydrate(
                draft.id(), draft.courseId(), draft.createdBy(), draft.draft(),
                TaskStatus.PUBLISHED, draft.referenceMaterial(), null, 1,
                NOW, null, draft.createdAt(), draft.updatedAt());
        tasks.save(published);
        if (assign) {
            assignments.saveAll(List.of(new TaskAssignment(
                    UUID.randomUUID(), id, STUDENT, CLASS, NOW, AssignmentStatus.ASSIGNED)));
        }
        return published;
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
