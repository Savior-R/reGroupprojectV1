package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.MaterialUpload;
import com.shijiangtai.m3classroom.application.command.UpdateTaskCommand;
import com.shijiangtai.m3classroom.application.port.AfterCommitPort;
import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.application.port.QuestionBankQueryPort;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.application.view.TaskViewMapper;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.CourseStudentInfo;
import com.shijiangtai.m3classroom.domain.task.PublishQuestion;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.ReferenceMaterial;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskPublishPolicy;
import com.shijiangtai.m3classroom.domain.task.TaskPublishedEvent;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.EventPublisher;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskPublishServiceImpl implements TaskPublishService {
    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository assignmentRepository;
    private final CourseQueryPort courseQueryPort;
    private final QuestionBankQueryPort questionBankQueryPort;
    private final TaskAccessPolicy accessPolicy;
    private final TaskPublishPolicy publishPolicy;
    private final TaskSnapshotFactory snapshotFactory;
    private final TaskDraftFactory draftFactory;
    private final ReferenceMaterialFactory materialFactory;
    private final SessionRepository sessionRepository;
    private final SubmissionRepository submissionRepository;
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;
    private final EventPublisher eventPublisher;
    private final AfterCommitPort afterCommitPort;

    public TaskPublishServiceImpl(
            TaskRepository taskRepository,
            TaskAssignmentRepository assignmentRepository,
            CourseQueryPort courseQueryPort,
            QuestionBankQueryPort questionBankQueryPort,
            TaskAccessPolicy accessPolicy,
            TaskPublishPolicy publishPolicy,
            TaskSnapshotFactory snapshotFactory,
            TaskDraftFactory draftFactory,
            ReferenceMaterialFactory materialFactory,
            SessionRepository sessionRepository,
            SubmissionRepository submissionRepository,
            IdGenerator idGenerator,
            ClockProvider clockProvider,
            EventPublisher eventPublisher,
            AfterCommitPort afterCommitPort) {
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.courseQueryPort = courseQueryPort;
        this.questionBankQueryPort = questionBankQueryPort;
        this.accessPolicy = accessPolicy;
        this.publishPolicy = publishPolicy;
        this.snapshotFactory = snapshotFactory;
        this.draftFactory = draftFactory;
        this.materialFactory = materialFactory;
        this.sessionRepository = sessionRepository;
        this.submissionRepository = submissionRepository;
        this.idGenerator = idGenerator;
        this.clockProvider = clockProvider;
        this.eventPublisher = eventPublisher;
        this.afterCommitPort = afterCommitPort;
    }

    @Override
    @Transactional
    public TaskView createPrepared(CurrentUser teacher, CreateTaskCommand command) {
        Objects.requireNonNull(command, "command");
        CourseInfo course = accessPolicy.requireTeacherCourse(teacher, command.courseId());
        TaskDraft draft = draftFactory.fromCreate(command);
        Task task = Task.create(
                new TaskId(idGenerator.nextId()), command.courseId(), teacher.accountId(),
                draft, clockProvider.now());
        taskRepository.save(task);
        return TaskViewMapper.toView(task);
    }

    @Override
    @Transactional
    public TaskView createAndPublish(
            CurrentUser teacher, CreateTaskCommand command, MaterialUpload upload) {
        Objects.requireNonNull(command, "command");
        CourseInfo course = accessPolicy.requireTeacherCourse(teacher, command.courseId());
        TaskId taskId = new TaskId(idGenerator.nextId());
        Instant now = clockProvider.now();
        TaskDraft draft = draftFactory.fromCreate(command);
        Task task = Task.create(taskId, command.courseId(), teacher.accountId(), draft, now);
        ReferenceMaterial material = prepareMaterial(course, taskId, draft.mode(), upload);
        try {
            if (material != null) {
                task.attachMaterial(material, now);
            }
            List<CourseStudentInfo> students = courseQueryPort.activeStudents(command.courseId());
            List<PublishQuestion> questions = questionsFor(course, draft);
            publishPolicy.validateForPublish(task, course, questions, students, now);
            TaskSnapshot snapshot = snapshotFactory.create(task, course, questions, now);
            List<TaskAssignment> assignments = assignments(task, students, now);
            task.publishInitial(snapshot, material, now);
            taskRepository.save(task);
            assignmentRepository.saveAll(assignments);
            eventPublisher.publish(new TaskPublishedEvent(
                    task.id().value(), task.courseId().value(), course.schoolId(), now,
                    assignments.size()));
            return TaskViewMapper.toView(task);
        } catch (RuntimeException exception) {
            materialFactory.deleteQuietly(material);
            throw exception;
        }
    }

    @Override
    @Transactional
    public TaskView updateAndPublish(
            CurrentUser teacher, TaskId taskId, UpdateTaskCommand command, MaterialUpload upload) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(command, "command");
        return taskRepository.withLock(taskId, task -> {
            CourseInfo course = accessPolicy.requireTeacherCourse(teacher, task.courseId());
            if (task.status() == TaskStatus.DELETED) {
                throw new DomainException(ErrorCode.TASK_DELETED, "任务已删除");
            }
            if (task.status() == TaskStatus.CLOSED) {
                throw new DomainException(ErrorCode.TASK_CLOSED, "任务已关闭");
            }
            if (sessionRepository.countByTask(taskId) > 0) {
                throw new DomainException(ErrorCode.TASK_HAS_SESSIONS,
                        "任务已有试讲记录，不能编辑");
            }
            if (submissionRepository.countByTask(taskId) > 0) {
                throw new DomainException(ErrorCode.TASK_HAS_SUBMISSIONS,
                        "任务已有提交，不能编辑");
            }
            Instant now = clockProvider.now();
            TaskDraft newDraft = draftFactory.fromUpdate(task.draft(), command);
            ReferenceMaterial oldMaterial = task.referenceMaterial();
            ReferenceMaterial createdMaterial = null;
            try {
                ReferenceMaterial newMaterial = oldMaterial;
                if (upload != null) {
                    createdMaterial = prepareMaterial(course, taskId, newDraft.mode(), upload);
                    newMaterial = createdMaterial;
                }
                if (newDraft.mode() == TaskMode.INTERVIEW) {
                    newMaterial = null;
                }
                if (newDraft.mode().requiresMaterial() && newMaterial == null) {
                    throw new DomainException(ErrorCode.REFERENCE_MATERIAL_MISSING,
                            "参考材料缺失", Map.of("field", "reference_material"));
                }
                task.updateConfiguration(newDraft, newMaterial, now);
                List<CourseStudentInfo> students =
                        courseQueryPort.activeStudents(task.courseId());
                List<PublishQuestion> questions = questionsFor(course, newDraft);
                publishPolicy.validateForPublish(task, course, questions, students, now);
                TaskSnapshot snapshot = snapshotFactory.create(task, course, questions, now);
                assignmentRepository.deleteByTask(taskId);
                assignmentRepository.saveAll(assignments(task, students, now));
                task.republish(snapshot, now);
                taskRepository.save(task);
                if (oldMaterial != null && oldMaterial != newMaterial) {
                    afterCommitPort.afterCommit(() -> materialFactory.deleteQuietly(oldMaterial));
                }
                return TaskViewMapper.toView(task);
            } catch (RuntimeException exception) {
                materialFactory.deleteQuietly(createdMaterial);
                throw exception;
            }
        });
    }

    /**
     * Internal/test-support entry for a task created without a snapshot. The public API creates
     * and publishes in one step; this method is not exposed by TaskController.
     */
    @Override
    @Transactional
    public TaskView publish(CurrentUser teacher, TaskId taskId) {
        Objects.requireNonNull(teacher, "teacher");
        Objects.requireNonNull(taskId, "taskId");
        return taskRepository.withLock(taskId, task -> {
            CourseInfo course = accessPolicy.requireTeacherCourse(teacher, task.courseId());
            if (task.status() == TaskStatus.DELETED) {
                throw new DomainException(ErrorCode.TASK_DELETED, "任务已删除");
            }
            if (task.status() == TaskStatus.CLOSED) {
                throw new DomainException(ErrorCode.TASK_CLOSED, "任务已关闭");
            }
            if (task.snapshot() != null) {
                throw new DomainException(ErrorCode.TASK_VERSION_CONFLICT,
                        "任务已发布", Map.of("task_id", taskId.value()));
            }
            Instant now = clockProvider.now();
            if (task.draft().mode().requiresMaterial() && task.referenceMaterial() == null) {
                throw new DomainException(ErrorCode.REFERENCE_MATERIAL_MISSING,
                        "参考材料缺失", Map.of("field", "reference_material"));
            }
            List<CourseStudentInfo> students = courseQueryPort.activeStudents(task.courseId());
            List<PublishQuestion> questions = questionsFor(course, task.draft());
            publishPolicy.validateForPublish(task, course, questions, students, now);
            TaskSnapshot snapshot = snapshotFactory.create(task, course, questions, now);
            List<TaskAssignment> assignments = assignments(task, students, now);
            task.publishInitial(snapshot, task.referenceMaterial(), now);
            taskRepository.save(task);
            assignmentRepository.saveAll(assignments);
            eventPublisher.publish(new TaskPublishedEvent(
                    task.id().value(), task.courseId().value(), course.schoolId(), now,
                    assignments.size()));
            return TaskViewMapper.toView(task);
        });
    }

    @Override
    @Transactional
    public void close(CurrentUser teacher, TaskId taskId) {
        Objects.requireNonNull(teacher, "teacher");
        Objects.requireNonNull(taskId, "taskId");
        taskRepository.withLock(taskId, task -> {
            accessPolicy.requireTeacherCourse(teacher, task.courseId());
            task.close(clockProvider.now());
            taskRepository.save(task);
            return null;
        });
    }

    private ReferenceMaterial prepareMaterial(
            CourseInfo course, TaskId taskId, TaskMode mode, MaterialUpload upload) {
        if (mode == TaskMode.INTERVIEW) {
            if (upload != null) {
                throw new DomainException(ErrorCode.MATERIAL_NOT_APPLICABLE,
                        "结构化面试不需要参考材料");
            }
            return null;
        }
        if (upload == null) {
            throw new DomainException(ErrorCode.REFERENCE_MATERIAL_MISSING,
                    "参考材料缺失", Map.of("field", "reference_material"));
        }
        return materialFactory.create(course, taskId, upload);
    }

    private List<PublishQuestion> questionsFor(CourseInfo course, TaskDraft draft) {
        if (draft.mode() != TaskMode.INTERVIEW) {
            return List.of();
        }
        QuestionConfig questionConfig = draft.questionConfig();
        if (questionConfig == null) {
            return List.of();
        }
        return questionBankQueryPort.getForPublish(
                new com.shijiangtai.shared.domain.SchoolId(course.schoolId()),
                questionConfig.questionIds());
    }

    private List<TaskAssignment> assignments(
            Task task, List<CourseStudentInfo> students, Instant now) {
        return students.stream()
                .map(student -> new TaskAssignment(
                        idGenerator.nextId(),
                        task.id(),
                        new AccountId(student.accountId()),
                        student.classId() == null ? null : new ClassId(student.classId()),
                        now,
                        AssignmentStatus.ASSIGNED))
                .toList();
    }
}
