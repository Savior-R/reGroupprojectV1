package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.application.port.QuestionBankQueryPort;
import com.shijiangtai.m3classroom.application.view.PublishResult;
import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.CourseStudentInfo;
import com.shijiangtai.m3classroom.domain.task.PublishQuestion;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskPublishPolicy;
import com.shijiangtai.m3classroom.domain.task.TaskPublishedEvent;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.EventPublisher;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.List;
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
    private final IdGenerator idGenerator;
    private final ClockProvider clockProvider;
    private final EventPublisher eventPublisher;

    public TaskPublishServiceImpl(
            TaskRepository taskRepository,
            TaskAssignmentRepository assignmentRepository,
            CourseQueryPort courseQueryPort,
            QuestionBankQueryPort questionBankQueryPort,
            TaskAccessPolicy accessPolicy,
            TaskPublishPolicy publishPolicy,
            TaskSnapshotFactory snapshotFactory,
            IdGenerator idGenerator,
            ClockProvider clockProvider,
            EventPublisher eventPublisher) {
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.courseQueryPort = courseQueryPort;
        this.questionBankQueryPort = questionBankQueryPort;
        this.accessPolicy = accessPolicy;
        this.publishPolicy = publishPolicy;
        this.snapshotFactory = snapshotFactory;
        this.idGenerator = idGenerator;
        this.clockProvider = clockProvider;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public PublishResult publish(CurrentUser teacher, TaskId taskId) {
        Objects.requireNonNull(teacher, "teacher");
        Objects.requireNonNull(taskId, "taskId");
        return taskRepository.withLock(taskId, task -> {
            CourseInfo course = accessPolicy.requireTeacherCourse(teacher, task.courseId());
            Instant now = clockProvider.now();
            List<CourseStudentInfo> students = courseQueryPort.activeStudents(task.courseId());
            List<PublishQuestion> questions = List.of();
            if (task.draft().mode() == TaskMode.INTERVIEW) {
                QuestionConfig questionConfig = task.draft().questionConfig();
                if (questionConfig != null) {
                    questions = questionBankQueryPort.getForPublish(
                            new com.shijiangtai.shared.domain.SchoolId(course.schoolId()),
                            questionConfig.questionIds());
                }
            }
            publishPolicy.validateForPublish(task, course, questions, students, now);
            TaskSnapshot snapshot = snapshotFactory.create(task, course, questions, now);
            List<TaskAssignment> newAssignments = students.stream()
                    .map(student -> new TaskAssignment(
                            idGenerator.nextId(),
                            task.id(),
                            new AccountId(student.accountId()),
                            student.classId() == null ? null : new ClassId(student.classId()),
                            now,
                            AssignmentStatus.ASSIGNED))
                    .toList();
            assignmentRepository.saveAll(newAssignments);
            task.publish(snapshot, now);
            taskRepository.save(task);
            eventPublisher.publish(new TaskPublishedEvent(
                    task.id().value(),
                    task.courseId().value(),
                    course.schoolId(),
                    now,
                    newAssignments.size()));
            return new PublishResult(
                    task.id().value(),
                    task.status().wireValue(),
                    task.configVersion(),
                    newAssignments.size(),
                    now);
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
}
