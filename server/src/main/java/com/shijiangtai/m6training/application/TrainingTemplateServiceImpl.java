package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.command.PublishTrainingTemplateCommand;
import com.shijiangtai.m6training.application.view.StudentTemplateCardView;
import com.shijiangtai.m6training.application.view.StudentTemplateDetailView;
import com.shijiangtai.m6training.application.view.TrainingTemplateView;
import com.shijiangtai.m6training.domain.TrainingConfigSnapshot;
import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingTemplateRepository;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort.CourseInfo;
import com.shijiangtai.m6training.domain.port.SourceTaskPort;
import com.shijiangtai.m6training.domain.port.SourceTaskPort.SourceTask;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.PageResult;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrainingTemplateServiceImpl implements TrainingTemplateService {
    private final TrainingTemplateRepository templateRepository;
    private final TrainingTemplateLifecycle lifecycle;
    private final SourceTaskPort sourceTaskPort;
    private final CourseDirectoryPort courseDirectoryPort;
    private final TrainingConfigFactory configFactory;
    private final M6Access access;
    private final IdGenerator idGenerator;
    private final ClockProvider clock;

    public TrainingTemplateServiceImpl(
            TrainingTemplateRepository templateRepository,
            TrainingTemplateLifecycle lifecycle,
            SourceTaskPort sourceTaskPort,
            CourseDirectoryPort courseDirectoryPort,
            TrainingConfigFactory configFactory,
            M6Access access,
            IdGenerator idGenerator,
            ClockProvider clock) {
        this.templateRepository = templateRepository;
        this.lifecycle = lifecycle;
        this.sourceTaskPort = sourceTaskPort;
        this.courseDirectoryPort = courseDirectoryPort;
        this.configFactory = configFactory;
        this.access = access;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TrainingTemplateView publish(CurrentUser user, PublishTrainingTemplateCommand command) {
        access.requireTeacher(user);
        TrainingLevel level = parseLevel(command.trainingLevel());
        if (command.sourceTaskId() == null || command.courseId() == null) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "源任务与目标课程必填", Map.of("field", "source_task_id/course_id"));
        }
        UUID teacherId = user.accountId().value();
        SourceTask task = sourceTaskPort.findTask(command.sourceTaskId())
                .orElseThrow(() -> new TrainingException(TrainingError.TRAINING_SOURCE_INVALID,
                        "源任务不存在", Map.of("source_task_id", command.sourceTaskId().toString())));
        if (!task.schoolId().equals(user.schoolId().value())) {
            throw new TrainingException(TrainingError.CROSS_SCHOOL_ACCESS,
                    "不能跨校使用源任务", Map.of());
        }
        if (!"published".equals(task.status()) && !"closed".equals(task.status())) {
            throw new TrainingException(TrainingError.TRAINING_SOURCE_INVALID,
                    "源任务尚未发布", Map.of("status", String.valueOf(task.status())));
        }
        if (!"free".equals(task.mode())) {
            throw new TrainingException(TrainingError.TRAINING_SOURCE_INVALID,
                    "仅自由试讲可生成训练模板", Map.of("mode", String.valueOf(task.mode())));
        }
        if (!courseDirectoryPort.isActiveTeacher(task.courseId(), teacherId)) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "无权访问源任务课程", Map.of("course_id", task.courseId().toString()));
        }
        CourseInfo targetCourse = courseDirectoryPort.findCourse(command.courseId())
                .orElseThrow(() -> new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                        "目标课程不存在", Map.of("course_id", command.courseId().toString())));
        if (!targetCourse.schoolId().equals(user.schoolId().value())) {
            throw new TrainingException(TrainingError.CROSS_SCHOOL_ACCESS,
                    "不能跨校发布模板", Map.of());
        }
        Instant now = clock.now();
        if (!targetCourse.active()
                || (targetCourse.endAt() != null && !targetCourse.endAt().isAfter(now))) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "目标课程不可用", Map.of("course_id", targetCourse.id().toString()));
        }
        if (!courseDirectoryPort.isActiveTeacher(targetCourse.id(), teacherId)) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "不在目标课程老师组", Map.of("course_id", targetCourse.id().toString()));
        }
        if (templateRepository.existsPublished(task.id(), targetCourse.id(), level)) {
            throw new TrainingException(TrainingError.TRAINING_TEMPLATE_EXISTS,
                    "该源任务在本课程与档位下已发布模板", Map.of());
        }
        TrainingConfigSnapshot config =
                configFactory.fromSourceTask(task, targetCourse.id(), level);
        TrainingTemplate template = TrainingTemplate.create(
                idGenerator.nextId(), task.id(), task.configVersion(), targetCourse.id(),
                level, config, teacherId, now);
        try {
            templateRepository.save(template);
        } catch (DataIntegrityViolationException exception) {
            throw new TrainingException(TrainingError.TRAINING_TEMPLATE_EXISTS,
                    "该源任务在本课程与档位下已发布模板", Map.of());
        }
        return toView(template);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TrainingTemplateView> listMine(
            CurrentUser user, String level, UUID courseId, int page, int size) {
        access.requireTeacher(user);
        TrainingLevel parsedLevel = level == null || level.isBlank() ? null : parseLevel(level);
        PageResult<TrainingTemplate> result = templateRepository.listByCreator(
                user.accountId().value(), parsedLevel, courseId, page, size);
        return new PageResult<>(
                result.items().stream().map(this::toView).toList(),
                result.total(), result.page(), result.size());
    }

    @Override
    @Transactional
    public void unpublish(CurrentUser user, UUID templateId) {
        access.requireTeacher(user);
        TrainingTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> notFound(templateId));
        if (!template.createdBy().equals(user.accountId().value())) {
            throw new TrainingException(TrainingError.FORBIDDEN,
                    "只能下架自己创建的模板", Map.of("template_id", templateId.toString()));
        }
        lifecycle.unpublish(template, clock.now());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StudentTemplateCardView> listForStudent(
            CurrentUser user, String level, UUID courseId, int page, int size) {
        access.requireStudent(user);
        TrainingLevel parsedLevel = level == null || level.isBlank() ? null : parseLevel(level);
        PageResult<UUID> ids = templateRepository.listStudentVisibleIds(
                user.accountId().value(), user.schoolId().value(), parsedLevel,
                courseId, page, size);
        Map<UUID, CourseInfo> courses = new HashMap<>();
        List<StudentTemplateCardView> items = ids.items().stream()
                .map(templateRepository::findById)
                .flatMap(Optional::stream)
                .filter(TrainingTemplate::isPublished)
                .map(template -> {
                    CourseInfo course = courses.computeIfAbsent(template.courseId(),
                            id -> courseDirectoryPort.findCourse(id).orElse(null));
                    if (course == null) {
                        return null;
                    }
                    return new StudentTemplateCardView(
                            template.id(),
                            template.config().topic(),
                            template.courseId(),
                            course.name(),
                            template.trainingLevel(),
                            template.config().duration().maxMillis(),
                            template.config().virtualStudents().size());
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return new PageResult<>(items, ids.total(), ids.page(), ids.size());
    }

    @Override
    @Transactional(readOnly = true)
    public StudentTemplateDetailView getForStudent(CurrentUser user, UUID templateId) {
        access.requireStudent(user);
        TrainingTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> notFound(templateId));
        CourseInfo course = courseDirectoryPort.findCourse(template.courseId())
                .orElseThrow(() -> new TrainingException(
                        TrainingError.TRAINING_TEMPLATE_UNAVAILABLE, "目标课程不存在", Map.of()));
        if (!course.schoolId().equals(user.schoolId().value())) {
            throw notFound(templateId);
        }
        if (!template.isPublished()) {
            throw new TrainingException(TrainingError.TRAINING_TEMPLATE_UNAVAILABLE,
                    "模板已下架", Map.of("template_id", templateId.toString()));
        }
        Instant now = clock.now();
        if (!course.active() || (course.endAt() != null && !course.endAt().isAfter(now))) {
            throw new TrainingException(TrainingError.TRAINING_TEMPLATE_UNAVAILABLE,
                    "目标课程不可用", Map.of("course_id", course.id().toString()));
        }
        if (!courseDirectoryPort.isActiveStudent(course.id(), user.accountId().value())) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "不在目标课程学生名单", Map.of("course_id", course.id().toString()));
        }
        TrainingConfigSnapshot config = template.config();
        return new StudentTemplateDetailView(
                template.id(),
                config.topic(),
                template.courseId(),
                course.name(),
                template.trainingLevel(),
                config.duration().maxMillis(),
                List.copyOf(config.weights().keySet()),
                config.virtualStudents());
    }

    private TrainingTemplateView toView(TrainingTemplate template) {
        TrainingConfigSnapshot config = template.config();
        return new TrainingTemplateView(
                template.id(),
                template.sourceTaskId(),
                template.sourceTaskConfigVersion(),
                template.courseId(),
                template.trainingLevel(),
                template.status().wireValue(),
                config.topic(),
                config.duration().recommendedSeconds(),
                config.duration().maxSeconds(),
                config.virtualStudents().size(),
                config.referenceMaterial() != null,
                config.weights(),
                template.publishedAt(),
                template.unpublishedAt(),
                template.createdAt());
    }

    private TrainingLevel parseLevel(String level) {
        if (level == null || level.isBlank()) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "训练档位必填", Map.of("field", "training_level"));
        }
        return TrainingLevel.fromWire(level);
    }

    private TrainingException notFound(UUID templateId) {
        return new TrainingException(TrainingError.RESOURCE_NOT_FOUND,
                "模板不存在", Map.of("template_id", templateId.toString()));
    }
}
