package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.command.CreateTrainingSessionCommand;
import com.shijiangtai.m6training.application.view.TrainingPublicConfig;
import com.shijiangtai.m6training.application.view.TrainingSessionView;
import com.shijiangtai.m6training.domain.TrainingEffectiveConfig;
import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingMaterial;
import com.shijiangtai.m6training.domain.TrainingMaterialRepository;
import com.shijiangtai.m6training.domain.TrainingReferenceMaterial;
import com.shijiangtai.m6training.domain.TrainingSessionMeta;
import com.shijiangtai.m6training.domain.TrainingSessionMetaRepository;
import com.shijiangtai.m6training.domain.TrainingSessionStatus;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingTemplateRepository;
import com.shijiangtai.m6training.domain.TrainingVirtualStudent;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort.CourseInfo;
import com.shijiangtai.m6training.domain.port.TrainingClassroomPort;
import com.shijiangtai.m6training.domain.port.TrainingClassroomPort.ClassroomSession;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrainingSessionServiceImpl implements TrainingSessionService {
    private final TrainingTemplateRepository templateRepository;
    private final TrainingMaterialRepository materialRepository;
    private final TrainingSessionMetaRepository sessionMetaRepository;
    private final TrainingClassroomPort classroomPort;
    private final CourseDirectoryPort courseDirectoryPort;
    private final TrainingConfigFactory configFactory;
    private final M6Access access;
    private final IdGenerator idGenerator;
    private final ClockProvider clock;

    public TrainingSessionServiceImpl(
            TrainingTemplateRepository templateRepository,
            TrainingMaterialRepository materialRepository,
            TrainingSessionMetaRepository sessionMetaRepository,
            TrainingClassroomPort classroomPort,
            CourseDirectoryPort courseDirectoryPort,
            TrainingConfigFactory configFactory,
            M6Access access,
            IdGenerator idGenerator,
            ClockProvider clock) {
        this.templateRepository = templateRepository;
        this.materialRepository = materialRepository;
        this.sessionMetaRepository = sessionMetaRepository;
        this.classroomPort = classroomPort;
        this.courseDirectoryPort = courseDirectoryPort;
        this.configFactory = configFactory;
        this.access = access;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TrainingSessionView create(CurrentUser user, CreateTrainingSessionCommand command) {
        access.requireStudent(user);
        UUID studentId = user.accountId().value();
        Instant now = clock.now();

        TrainingEffectiveConfig config;
        TrainingLevel level;
        UUID templateId;
        UUID courseId;
        TrainingMaterial material = null;

        if (command.templateId() != null) {
            TrainingTemplate template = templateRepository.findById(command.templateId())
                    .orElseThrow(() -> new TrainingException(
                            TrainingError.TRAINING_TEMPLATE_UNAVAILABLE,
                            "模板不存在", Map.of("template_id", command.templateId().toString())));
            if (!template.isPublished()) {
                throw new TrainingException(TrainingError.TRAINING_TEMPLATE_UNAVAILABLE,
                        "模板已下架", Map.of("template_id", template.id().toString()));
            }
            CourseInfo course = courseDirectoryPort.findCourse(template.courseId())
                    .orElseThrow(() -> new TrainingException(
                            TrainingError.TRAINING_TEMPLATE_UNAVAILABLE,
                            "目标课程不存在", Map.of()));
            if (!course.schoolId().equals(user.schoolId().value())) {
                throw new TrainingException(TrainingError.CROSS_SCHOOL_ACCESS,
                        "不能跨校训练", Map.of());
            }
            if (!course.active()
                    || (course.endAt() != null && !course.endAt().isAfter(now))) {
                throw new TrainingException(TrainingError.TRAINING_TEMPLATE_UNAVAILABLE,
                        "目标课程不可用", Map.of("course_id", course.id().toString()));
            }
            if (!courseDirectoryPort.isActiveStudent(course.id(), studentId)) {
                throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                        "不在目标课程学生名单", Map.of("course_id", course.id().toString()));
            }
            level = template.trainingLevel();
            if (command.trainingLevel() != null && !command.trainingLevel().isBlank()) {
                TrainingLevel requested = TrainingLevel.fromWire(command.trainingLevel());
                if (requested != level) {
                    throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                            "训练档位必须与模板一致", Map.of("field", "training_level"));
                }
            }
            List<TrainingVirtualStudent> students =
                    configFactory.toDomain(command.virtualStudents());
            config = configFactory.fromTemplate(template, students);
            templateId = template.id();
            courseId = template.courseId();
        } else {
            level = requireLevel(command.trainingLevel());
            courseId = command.courseId();
            if (courseId != null) {
                CourseInfo course = requireUsableCourse(courseId, user, studentId, now);
                courseId = course.id();
            }
            if (command.materialId() != null) {
                material = requireMaterial(command.materialId(), studentId, now);
            }
            List<TrainingVirtualStudent> students =
                    configFactory.toDomain(command.virtualStudents());
            config = configFactory.free(
                    level, courseId, command.topic(), students, toReference(material));
            templateId = null;
        }

        UUID sessionId = idGenerator.nextId();
        try {
            classroomPort.create(sessionId, studentId, courseId, now);
            sessionMetaRepository.save(new TrainingSessionMeta(
                    sessionId, templateId, level, config, now));
        } catch (DataIntegrityViolationException exception) {
            throw new TrainingException(TrainingError.TRAINING_SESSION_ACTIVE,
                    "已有进行中的训练 session", Map.of());
        }
        if (material != null) {
            material.markUsed();
            materialRepository.save(material);
        }
        ClassroomSession created = classroomPort.find(sessionId)
                .orElseThrow(() -> new TrainingException(TrainingError.INTERNAL_ERROR,
                        "训练 session 创建失败", Map.of()));
        return toView(created, new TrainingSessionMeta(
                sessionId, templateId, level, config, now));
    }

    @Override
    @Transactional(readOnly = true)
    public TrainingSessionView get(CurrentUser user, UUID sessionId) {
        access.requireStudent(user);
        ClassroomSession session = requireOwnedSession(user, sessionId);
        TrainingSessionMeta meta = sessionMetaRepository.findBySessionId(sessionId)
                .orElseThrow(() -> notFound(sessionId));
        return toView(session, meta);
    }

    @Override
    @Transactional
    public TrainingSessionView end(CurrentUser user, UUID sessionId) {
        access.requireStudent(user);
        ClassroomSession session = requireOwnedSession(user, sessionId);
        TrainingSessionMeta meta = sessionMetaRepository.findBySessionId(sessionId)
                .orElseThrow(() -> notFound(sessionId));
        if (TrainingSessionStatus.IN_PROGRESS.wireValue().equals(session.status())) {
            Instant now = clock.now();
            Long durationMs = session.startedAt() == null
                    ? null : Math.max(0L, Duration.between(session.startedAt(), now).toMillis());
            classroomPort.updateStatus(
                    sessionId, TrainingSessionStatus.ENDED.wireValue(), now, durationMs);
            session = classroomPort.find(sessionId).orElse(session);
        } else if (TrainingSessionStatus.PREPARING.wireValue().equals(session.status())) {
            classroomPort.updateStatus(
                    sessionId, TrainingSessionStatus.CANCELLED.wireValue(), clock.now(), null);
            session = classroomPort.find(sessionId).orElse(session);
        }
        return toView(session, meta);
    }

    @Override
    @Transactional
    public void cancel(CurrentUser user, UUID sessionId) {
        access.requireStudent(user);
        ClassroomSession session = requireOwnedSession(user, sessionId);
        if (TrainingSessionStatus.CANCELLED.wireValue().equals(session.status())) {
            return;
        }
        if (!TrainingSessionStatus.PREPARING.wireValue().equals(session.status())) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "只能取消未开始的训练", Map.of("status", session.status()));
        }
        classroomPort.updateStatus(
                sessionId, TrainingSessionStatus.CANCELLED.wireValue(), clock.now(), null);
    }

    private ClassroomSession requireOwnedSession(CurrentUser user, UUID sessionId) {
        ClassroomSession session = classroomPort.find(sessionId)
                .orElseThrow(() -> notFound(sessionId));
        if (!session.studentAccountId().equals(user.accountId().value())) {
            throw new TrainingException(TrainingError.FORBIDDEN,
                    "只能访问自己的训练", Map.of("session_id", sessionId.toString()));
        }
        return session;
    }

    private CourseInfo requireUsableCourse(
            UUID courseId, CurrentUser user, UUID studentId, Instant now) {
        CourseInfo course = courseDirectoryPort.findCourse(courseId)
                .orElseThrow(() -> new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                        "课程不存在", Map.of("course_id", courseId.toString())));
        if (!course.schoolId().equals(user.schoolId().value())) {
            throw new TrainingException(TrainingError.CROSS_SCHOOL_ACCESS,
                    "不能跨校训练", Map.of());
        }
        if (!course.active() || (course.endAt() != null && !course.endAt().isAfter(now))) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "课程不可用", Map.of("course_id", courseId.toString()));
        }
        if (!courseDirectoryPort.isActiveStudent(courseId, studentId)) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "不在课程学生名单", Map.of("course_id", courseId.toString()));
        }
        return course;
    }

    private TrainingMaterial requireMaterial(UUID materialId, UUID studentId, Instant now) {
        TrainingMaterial material = materialRepository.findByIdAndStudent(materialId, studentId)
                .orElseThrow(() -> new TrainingException(TrainingError.TRAINING_MATERIAL_INVALID,
                        "参考材料不存在", Map.of("material_id", materialId.toString())));
        if (!material.usableAt(now) || material.parsedText() == null
                || material.parsedText().isBlank()) {
            throw new TrainingException(TrainingError.TRAINING_MATERIAL_INVALID,
                    "参考材料已过期或未解析", Map.of("material_id", materialId.toString()));
        }
        return material;
    }

    private TrainingReferenceMaterial toReference(TrainingMaterial material) {
        if (material == null) {
            return null;
        }
        return new TrainingReferenceMaterial(
                material.filename(),
                material.objectKey(),
                material.mime(),
                material.size(),
                material.parsedText(),
                material.charCount());
    }

    private TrainingSessionView toView(ClassroomSession session, TrainingSessionMeta meta) {
        TrainingEffectiveConfig config = meta.effectiveConfig();
        TrainingPublicConfig publicConfig = TrainingPublicConfig.of(
                config.topic(),
                config.trainingLevel(),
                config.duration().recommendedMillis(),
                config.duration().maxMillis(),
                config.virtualStudents());
        return new TrainingSessionView(
                session.id(),
                session.status(),
                meta.templateId(),
                session.courseId(),
                config.trainingLevel(),
                config.duration().recommendedMillis(),
                config.duration().maxMillis(),
                publicConfig,
                session.createdAt(),
                session.startedAt(),
                session.endedAt());
    }

    private TrainingLevel requireLevel(String level) {
        if (level == null || level.isBlank()) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "自由训练必须选择档位", Map.of("field", "training_level"));
        }
        return TrainingLevel.fromWire(level);
    }

    private TrainingException notFound(UUID sessionId) {
        return new TrainingException(TrainingError.RESOURCE_NOT_FOUND,
                "训练 session 不存在", Map.of("session_id", sessionId.toString()));
    }
}
