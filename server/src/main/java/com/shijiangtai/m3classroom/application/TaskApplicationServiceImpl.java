package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.MaterialUpload;
import com.shijiangtai.m3classroom.application.command.StudentTaskQuery;
import com.shijiangtai.m3classroom.application.command.TaskQuery;
import com.shijiangtai.m3classroom.application.command.UpdateTaskCommand;
import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.application.view.StudentTaskDetailView;
import com.shijiangtai.m3classroom.application.view.StudentTaskView;
import com.shijiangtai.m3classroom.application.view.TaskAssignmentView;
import com.shijiangtai.m3classroom.application.view.TaskCardView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.application.view.TaskViewMapper;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.PageResult;
import com.shijiangtai.shared.domain.TaskId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskApplicationServiceImpl implements TaskApplicationService {
    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository assignmentRepository;
    private final CourseQueryPort courseQueryPort;
    private final TaskAccessPolicy accessPolicy;
    private final TaskPublishService taskPublishService;
    private final SessionRepository sessionRepository;
    private final ClockProvider clockProvider;

    public TaskApplicationServiceImpl(
            TaskRepository taskRepository,
            TaskAssignmentRepository assignmentRepository,
            CourseQueryPort courseQueryPort,
            TaskAccessPolicy accessPolicy,
            TaskPublishService taskPublishService,
            SessionRepository sessionRepository,
            ClockProvider clockProvider) {
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.courseQueryPort = courseQueryPort;
        this.accessPolicy = accessPolicy;
        this.taskPublishService = taskPublishService;
        this.sessionRepository = sessionRepository;
        this.clockProvider = clockProvider;
    }

    @Override
    @Transactional
    public TaskView create(
            CurrentUser teacher, CreateTaskCommand command, MaterialUpload material) {
        return taskPublishService.createAndPublish(teacher, command, material);
    }

    @Override
    @Transactional
    public TaskView createPreparedTask(CurrentUser teacher, CreateTaskCommand command) {
        return taskPublishService.createPrepared(teacher, command);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskView get(CurrentUser teacher, TaskId taskId) {
        Task task = findVisibleTask(taskId);
        accessPolicy.requireTeacherTask(teacher, task);
        return TaskViewMapper.toView(task);
    }

    @Override
    @Transactional
    public TaskView update(
            CurrentUser teacher, TaskId taskId, UpdateTaskCommand command, MaterialUpload material) {
        return taskPublishService.updateAndPublish(teacher, taskId, command, material);
    }

    @Override
    @Transactional
    public void softDelete(CurrentUser teacher, TaskId taskId) {
        Objects.requireNonNull(teacher, "teacher");
        taskRepository.withLock(taskId, task -> {
            accessPolicy.requireTeacherCourse(teacher, task.courseId());
            if (task.status() == TaskStatus.DELETED) {
                throw new DomainException(ErrorCode.TASK_DELETED, "任务已删除");
            }
            if (sessionRepository.countByTask(taskId) > 0) {
                throw new DomainException(ErrorCode.TASK_HAS_SESSIONS,
                        "任务已有试讲记录，不能删除");
            }
            task.softDelete(teacher.accountId(), clockProvider.now());
            taskRepository.save(task);
            return null;
        });
    }

    @Override
    @Transactional
    public void close(CurrentUser teacher, TaskId taskId) {
        taskPublishService.close(teacher, taskId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TaskCardView> listCourseTasks(
            CurrentUser teacher, CourseId courseId, TaskQuery query) {
        accessPolicy.requireTeacherCourse(teacher, courseId);
        List<Task> tasks = taskRepository.findByCourse(courseId).stream()
                .filter(task -> task.status() != TaskStatus.DELETED)
                .filter(task -> query.status() == null || task.status() == query.status())
                .filter(task -> query.mode() == null || task.draft().mode() == query.mode())
                .sorted(Comparator.comparing(Task::updatedAt).reversed())
                .toList();
        List<TaskCardView> cards = tasks.stream()
                .map(task -> TaskViewMapper.toCard(
                        task,
                        assignmentRepository.countByTask(task.id()),
                        assignmentRepository.countByTaskAndStatus(
                                task.id(), AssignmentStatus.SUBMITTED)))
                .toList();
        return page(cards, query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StudentTaskView> listStudentTasks(
            CurrentUser student, StudentTaskQuery query) {
        requireStudent(student);
        List<StudentRow> rows = new ArrayList<>();
        for (TaskAssignment assignment : assignmentRepository.findByStudent(student.accountId())) {
            Task task = taskRepository.findById(assignment.taskId()).orElse(null);
            if (task == null || task.status() == TaskStatus.DELETED) {
                continue;
            }
            CourseInfo course = courseQueryPort.findCourse(task.courseId()).orElse(null);
            if (course == null || !course.schoolId().equals(student.schoolId().value())) {
                continue;
            }
            rows.add(new StudentRow(task, course, assignment));
        }
        rows.sort(Comparator.comparing(
                (StudentRow row) -> row.task().updatedAt()).reversed());
        List<StudentTaskView> views = rows.stream()
                .map(row -> TaskViewMapper.toStudentView(
                        row.task(), row.course(), row.assignment()))
                .toList();
        return page(views, query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public StudentTaskDetailView getStudentTask(CurrentUser student, TaskId taskId) {
        requireStudent(student);
        Task task = findVisibleTask(taskId);
        CourseInfo course = courseQueryPort.findCourse(task.courseId())
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "课程不存在", Map.of("course_id", task.courseId().value())));
        TaskAssignment assignment = assignmentRepository
                .find(taskId, student.accountId()).orElse(null);
        accessPolicy.requireStudentAssigned(student, course, assignment);
        return TaskViewMapper.toStudentDetail(task, course, assignment);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TaskAssignmentView> listAssignments(
            CurrentUser teacher, TaskId taskId, int page, int size) {
        Task task = findVisibleTask(taskId);
        accessPolicy.requireTeacherTask(teacher, task);
        List<TaskAssignmentView> views = assignmentRepository.findByTask(taskId).stream()
                .sorted(Comparator.comparing(TaskAssignment::assignedAt))
                .map(TaskViewMapper::toAssignment)
                .toList();
        return page(views, page, size);
    }

    private Task findVisibleTask(TaskId taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "任务不存在", Map.of("task_id", taskId.value())));
        if (task.status() == TaskStatus.DELETED) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                    "任务不存在", Map.of("task_id", taskId.value()));
        }
        return task;
    }

    private void requireStudent(CurrentUser user) {
        if (user.type() != AccountType.STUDENT) {
            throw new DomainException(ErrorCode.FORBIDDEN, "无权限访问任务");
        }
    }

    private <T> PageResult<T> page(List<T> items, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "分页参数非法", Map.of("page", page, "size", size));
        }
        int from = Math.min(Math.max((page - 1) * size, 0), items.size());
        int to = Math.min(from + size, items.size());
        return new PageResult<>(items.subList(from, to), items.size(), page, size);
    }

    private record StudentRow(Task task, CourseInfo course, TaskAssignment assignment) {
    }
}
