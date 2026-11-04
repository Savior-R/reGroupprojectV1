package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.Patch;
import com.shijiangtai.m3classroom.application.command.StudentTaskQuery;
import com.shijiangtai.m3classroom.application.command.TaskQuery;
import com.shijiangtai.m3classroom.application.command.UpdateTaskCommand;
import com.shijiangtai.m3classroom.application.command.VirtualStudentSpec;
import com.shijiangtai.m3classroom.application.port.AfterCommitPort;
import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import com.shijiangtai.m3classroom.application.view.StudentTaskDetailView;
import com.shijiangtai.m3classroom.application.view.StudentTaskView;
import com.shijiangtai.m3classroom.application.view.TaskAssignmentView;
import com.shijiangtai.m3classroom.application.view.TaskCardView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.application.view.TaskViewMapper;
import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.ReferenceMaterial;
import com.shijiangtai.m3classroom.domain.task.RubricWeights;
import com.shijiangtai.m3classroom.domain.task.ScenarioConfig;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
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
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskApplicationServiceImpl implements TaskApplicationService {
    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository assignmentRepository;
    private final CourseQueryPort courseQueryPort;
    private final TaskAccessPolicy accessPolicy;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;
    private final MaterialObjectStorage materialObjectStorage;
    private final AfterCommitPort afterCommitPort;

    public TaskApplicationServiceImpl(
            TaskRepository taskRepository,
            TaskAssignmentRepository assignmentRepository,
            CourseQueryPort courseQueryPort,
            TaskAccessPolicy accessPolicy,
            IdGenerator idGenerator,
            ClockProvider clockProvider,
            MaterialObjectStorage materialObjectStorage,
            AfterCommitPort afterCommitPort) {
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.courseQueryPort = courseQueryPort;
        this.accessPolicy = accessPolicy;
        this.idGenerator = idGenerator;
        this.clockProvider = clockProvider;
        this.materialObjectStorage = materialObjectStorage;
        this.afterCommitPort = afterCommitPort;
    }

    @Override
    @Transactional
    public TaskView createDraft(CurrentUser teacher, CreateTaskCommand command) {
        Objects.requireNonNull(command, "command");
        accessPolicy.requireTeacherCourse(teacher, command.courseId());
        Task task = Task.createDraft(
                new TaskId(idGenerator.nextId()), command.courseId(), teacher.accountId(),
                draftFromCreate(command), clockProvider.now());
        taskRepository.save(task);
        return TaskViewMapper.toView(task);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskView get(CurrentUser teacher, TaskId taskId) {
        Task task = findTask(taskId);
        accessPolicy.requireTeacherTask(teacher, task);
        return TaskViewMapper.toView(task);
    }

    @Override
    @Transactional
    public TaskView updateDraft(CurrentUser teacher, TaskId taskId, UpdateTaskCommand command) {
        Objects.requireNonNull(command, "command");
        Task task = findTask(taskId);
        accessPolicy.requireTeacherTask(teacher, task);
        task.assertDraft();
        ReferenceMaterial oldMaterial = task.referenceMaterial();
        TaskDraft newDraft = draftFromUpdate(task.draft(), command);
        boolean modeChanged = task.updateDraft(newDraft, clockProvider.now());
        taskRepository.save(task);
        if (modeChanged && oldMaterial != null) {
            String oldKey = oldMaterial.objectKey();
            afterCommitPort.afterCommit(() -> materialObjectStorage.delete(oldKey));
        }
        return TaskViewMapper.toView(task);
    }

    @Override
    @Transactional
    public void deleteDraft(CurrentUser teacher, TaskId taskId) {
        Task task = findTask(taskId);
        accessPolicy.requireTeacherTask(teacher, task);
        task.assertDraft();
        ReferenceMaterial oldMaterial = task.referenceMaterial();
        taskRepository.delete(taskId);
        assignmentRepository.deleteByTask(taskId);
        if (oldMaterial != null) {
            String oldKey = oldMaterial.objectKey();
            afterCommitPort.afterCommit(() -> materialObjectStorage.delete(oldKey));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TaskCardView> listCourseTasks(
            CurrentUser teacher, CourseId courseId, TaskQuery query) {
        accessPolicy.requireTeacherCourse(teacher, courseId);
        List<Task> tasks = taskRepository.findByCourse(courseId).stream()
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
            if (task == null || task.status() == TaskStatus.DRAFT) {
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
        Task task = findTask(taskId);
        if (task.status() == TaskStatus.DRAFT) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                    "任务不存在", Map.of("task_id", taskId.value()));
        }
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
        Task task = findTask(taskId);
        accessPolicy.requireTeacherTask(teacher, task);
        List<TaskAssignmentView> views = assignmentRepository.findByTask(taskId).stream()
                .sorted(Comparator.comparing(TaskAssignment::assignedAt))
                .map(TaskViewMapper::toAssignment)
                .toList();
        return page(views, page, size);
    }

    private Task findTask(TaskId taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "任务不存在", Map.of("task_id", taskId.value())));
    }

    private void requireStudent(CurrentUser user) {
        if (user.type() != AccountType.STUDENT) {
            throw new DomainException(ErrorCode.FORBIDDEN, "无权限访问任务");
        }
    }

    private TaskDraft draftFromCreate(CreateTaskCommand command) {
        TaskMode mode = command.mode();
        if (mode == null) {
            throw invalid("mode");
        }
        int recommended;
        int max;
        QuestionConfig questionConfig = command.questionConfig();
        if (mode == TaskMode.INTERVIEW) {
            int total;
            if (questionConfig != null) {
                total = questionConfig.totalDurationSeconds();
                if (command.durationRecommendedSeconds() != null
                        && !command.durationRecommendedSeconds().equals(total)) {
                    throw invalid("duration");
                }
                if (command.durationMaxSeconds() != null
                        && !command.durationMaxSeconds().equals(total)) {
                    throw invalid("duration");
                }
            } else {
                total = command.durationRecommendedSeconds() != null
                        ? command.durationRecommendedSeconds() : 300;
                questionConfig = new QuestionConfig(List.of(), total);
            }
            recommended = total;
            max = total;
        } else {
            recommended = command.durationRecommendedSeconds() != null
                    ? command.durationRecommendedSeconds() : mode.defaultRecommendedSeconds();
            max = command.durationMaxSeconds() != null
                    ? command.durationMaxSeconds() : mode.defaultMaxSeconds();
            if (questionConfig != null) {
                throw invalid("question_config");
            }
        }
        Map<String, Integer> weights = command.rubricWeights() != null
                ? command.rubricWeights() : mode.defaultWeights().asMap();
        return new TaskDraft(
                mode, command.topic(), command.textbookVersion(), recommended, max,
                RubricWeights.of(mode, weights), command.scenario(),
                toConfigs(command.virtualStudents()), questionConfig);
    }

    private TaskDraft draftFromUpdate(TaskDraft base, UpdateTaskCommand command) {
        TaskMode mode = command.mode().present() ? command.mode().value() : base.mode();
        if (mode == null) {
            throw invalid("mode");
        }
        boolean modeChanged = mode != base.mode();
        String topic = command.topic().present() ? command.topic().value() : base.topic();
        String textbook = command.textbookVersion().present()
                ? command.textbookVersion().value() : base.textbookVersion();

        if (modeChanged) {
            int recommended = command.durationRecommendedSeconds().present()
                    ? requireInt(command.durationRecommendedSeconds(), "duration")
                    : mode.defaultRecommendedSeconds();
            int max = command.durationMaxSeconds().present()
                    ? requireInt(command.durationMaxSeconds(), "duration")
                    : mode.defaultMaxSeconds();
            QuestionConfig questionConfig = null;
            if (mode == TaskMode.INTERVIEW) {
                int total = command.durationRecommendedSeconds().present()
                        ? recommended : 300;
                if (command.durationMaxSeconds().present() && max != total) {
                    throw invalid("duration");
                }
                questionConfig = new QuestionConfig(List.of(), total);
                recommended = total;
                max = total;
            }
            return new TaskDraft(mode, topic, textbook, recommended, max,
                    mode.defaultWeights(), null, List.of(), questionConfig);
        }

        int recommended = command.durationRecommendedSeconds().present()
                ? requireInt(command.durationRecommendedSeconds(), "duration")
                : base.durationRecommendedSeconds();
        int max = command.durationMaxSeconds().present()
                ? requireInt(command.durationMaxSeconds(), "duration")
                : base.durationMaxSeconds();
        Map<String, Integer> weights = command.rubricWeights().present()
                ? command.rubricWeights().value() : base.rubricWeights().asMap();
        ScenarioConfig scenario = command.scenario().present()
                ? command.scenario().value() : base.scenario();
        List<VirtualStudentConfig> students = command.virtualStudents().present()
                ? toConfigs(command.virtualStudents().value()) : base.virtualStudents();
        QuestionConfig questionConfig = command.questionConfig().present()
                ? command.questionConfig().value() : base.questionConfig();

        if (mode == TaskMode.INTERVIEW && command.questionConfig().present()) {
            if (questionConfig == null) {
                questionConfig = new QuestionConfig(
                        List.of(), base.durationRecommendedSeconds());
            }
            int total = questionConfig.totalDurationSeconds();
            if (command.durationRecommendedSeconds().present() && recommended != total) {
                throw invalid("duration");
            }
            if (command.durationMaxSeconds().present() && max != total) {
                throw invalid("duration");
            }
            recommended = total;
            max = total;
        }

        return new TaskDraft(mode, topic, textbook, recommended, max,
                RubricWeights.of(mode, weights), scenario, students, questionConfig);
    }

    private List<VirtualStudentConfig> toConfigs(List<VirtualStudentSpec> specs) {
        if (specs == null) {
            return List.of();
        }
        List<VirtualStudentConfig> result = new ArrayList<>();
        for (VirtualStudentSpec spec : specs) {
            if (spec == null) {
                throw invalid("virtual_students");
            }
            UUID id = spec.virtualStudentId() != null
                    ? spec.virtualStudentId() : idGenerator.nextId();
            result.add(new VirtualStudentConfig(
                    id, spec.name(), spec.avatar(), spec.personalityText(),
                    spec.activity(), spec.questionRate(), spec.ability(),
                    spec.attentionBaseline(), spec.disciplineProbability(),
                    spec.seatNo(), spec.sortOrder()));
        }
        return List.copyOf(result);
    }

    private int requireInt(Patch<Integer> patch, String field) {
        if (!patch.present() || patch.value() == null) {
            throw invalid(field);
        }
        return patch.value();
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

    private static DomainException invalid(String field) {
        return new DomainException(ErrorCode.TASK_CONFIG_INVALID,
                "任务配置非法", Map.of("field", field));
    }

    private record StudentRow(Task task, CourseInfo course, TaskAssignment assignment) {
    }
}
