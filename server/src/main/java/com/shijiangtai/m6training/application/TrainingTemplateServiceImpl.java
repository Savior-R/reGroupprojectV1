package com.shijiangtai.m6training.application;

import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import com.shijiangtai.m6training.application.command.CopyTrainingTemplateCommand;
import com.shijiangtai.m6training.application.command.CreateTrainingTemplateCommand;
import com.shijiangtai.m6training.application.command.UpdateTrainingTemplateCommand;
import com.shijiangtai.m6training.application.view.TaskTemplateConfigView;
import com.shijiangtai.m6training.application.view.TrainingMaterialRefView;
import com.shijiangtai.m6training.application.view.TrainingTemplateDetailView;
import com.shijiangtai.m6training.application.view.TrainingTemplateSummaryView;
import com.shijiangtai.m6training.domain.TrainingConfigSnapshot;
import com.shijiangtai.m6training.domain.TrainingDefaults;
import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingMaterial;
import com.shijiangtai.m6training.domain.TrainingMaterialRepository;
import com.shijiangtai.m6training.domain.TrainingMaterialStatus;
import com.shijiangtai.m6training.domain.TrainingMaterialUsage;
import com.shijiangtai.m6training.domain.TrainingOwnerRole;
import com.shijiangtai.m6training.domain.TrainingReferenceMaterial;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingTemplatePublication;
import com.shijiangtai.m6training.domain.TrainingTemplatePublicationRepository;
import com.shijiangtai.m6training.domain.TrainingTemplateRepository;
import com.shijiangtai.m6training.domain.TrainingTemplateStatus;
import com.shijiangtai.m6training.domain.TrainingVirtualStudent;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort.CourseInfo;
import com.shijiangtai.m6training.domain.port.TrainingMaterialObjectReader;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.PageResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrainingTemplateServiceImpl implements TrainingTemplateService {
    private static final Logger log = LoggerFactory.getLogger(TrainingTemplateServiceImpl.class);
    private static final String TEMPLATE_OBJECT_PREFIX = "training-template-materials/";
    private static final String COPY_SUFFIX = "（副本）";

    private final TrainingTemplateRepository templateRepository;
    private final TrainingTemplatePublicationRepository publicationRepository;
    private final TrainingMaterialRepository materialRepository;
    private final TrainingTemplateLifecycle lifecycle;
    private final TrainingConfigFactory configFactory;
    private final CourseDirectoryPort courseDirectoryPort;
    private final MaterialObjectStorage materialStorage;
    private final TrainingMaterialObjectReader materialObjectReader;
    private final M6Access access;
    private final IdGenerator idGenerator;
    private final ClockProvider clock;

    public TrainingTemplateServiceImpl(
            TrainingTemplateRepository templateRepository,
            TrainingTemplatePublicationRepository publicationRepository,
            TrainingMaterialRepository materialRepository,
            TrainingTemplateLifecycle lifecycle,
            TrainingConfigFactory configFactory,
            CourseDirectoryPort courseDirectoryPort,
            MaterialObjectStorage materialStorage,
            TrainingMaterialObjectReader materialObjectReader,
            M6Access access,
            IdGenerator idGenerator,
            ClockProvider clock) {
        this.templateRepository = templateRepository;
        this.publicationRepository = publicationRepository;
        this.materialRepository = materialRepository;
        this.lifecycle = lifecycle;
        this.configFactory = configFactory;
        this.courseDirectoryPort = courseDirectoryPort;
        this.materialStorage = materialStorage;
        this.materialObjectReader = materialObjectReader;
        this.access = access;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TrainingTemplateDetailView create(
            CurrentUser user, CreateTrainingTemplateCommand command) {
        TrainingOwnerRole role = resolveRole(user, command.ownerRole());
        UUID ownerId = user.accountId().value();
        TrainingLevel level = requireLevel(command.trainingLevel());
        TrainingMaterial material = command.materialId() == null
                ? null : requireUploadedTemplateMaterial(command.materialId(), ownerId);
        List<TrainingVirtualStudent> students =
                configFactory.toDomain(command.virtualStudents());
        TrainingConfigSnapshot config = configFactory.snapshot(
                level,
                command.topic(),
                command.textbookVersion(),
                command.recommendedSeconds(),
                command.maxSeconds(),
                command.weights(),
                students,
                configFactory.toReference(material));

        Instant now = clock.now();
        TrainingTemplate template = createWithinQuota(ownerId, () -> TrainingTemplate.createManual(
                idGenerator.nextId(),
                user.schoolId().value(),
                ownerId,
                role,
                command.name(),
                level,
                config,
                now));
        templateRepository.save(template);
        attachMaterial(template.id(), material);
        return detail(template);
    }

    @Override
    @Transactional
    public TrainingTemplateDetailView copy(
            CurrentUser user, CopyTrainingTemplateCommand command) {
        access.requireStudent(user);
        if (command.publicationId() == null) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "publication_id 必填", Map.of("field", "publication_id"));
        }
        UUID studentId = user.accountId().value();
        Instant now = clock.now();
        TrainingTemplatePublication publication = publicationRepository
                .findById(command.publicationId())
                .orElseThrow(() -> publicationNotFound(command.publicationId()));
        if (!publication.isPublished()) {
            throw new TrainingException(TrainingError.TRAINING_PUBLICATION_UNAVAILABLE,
                    "发布已下架", Map.of("publication_id", publication.id().toString()));
        }
        TrainingTemplate source = templateRepository.findById(publication.templateId())
                .filter(TrainingTemplate::isActive)
                .filter(template -> template.ownerRole() == TrainingOwnerRole.TEACHER)
                .orElseThrow(() -> new TrainingException(
                        TrainingError.TRAINING_TEMPLATE_UNAVAILABLE,
                        "模板已删除或不可用",
                        Map.of("publication_id", publication.id().toString())));
        requireVisibleCourse(user, source, publication.courseId(), now);

        templateRepository.lockOwner(studentId);
        if (templateRepository.countActiveByOwner(studentId)
                >= TrainingDefaults.MAX_ACTIVE_TEMPLATES) {
            throw limitReached();
        }

        TrainingConfigSnapshot sourceConfig = source.config();
        TrainingMaterial copiedMaterial = null;
        TrainingReferenceMaterial reference = sourceConfig.referenceMaterial();
        if (reference != null) {
            copiedMaterial = copyMaterial(
                    user.schoolId().value(), studentId, reference, now);
            reference = configFactory.toReference(copiedMaterial);
        }
        String name = command.name() == null || command.name().isBlank()
                ? defaultCopyName(source.name()) : command.name();
        TrainingConfigSnapshot config = configFactory.snapshot(
                source.trainingLevel(),
                sourceConfig.topic(),
                sourceConfig.textbookVersion(),
                sourceConfig.duration().recommendedSeconds(),
                sourceConfig.duration().maxSeconds(),
                sourceConfig.weights(),
                sourceConfig.virtualStudents(),
                reference);
        TrainingTemplate copy = TrainingTemplate.createCopy(
                idGenerator.nextId(),
                user.schoolId().value(),
                studentId,
                name,
                source.trainingLevel(),
                config,
                source.id(),
                publication.id(),
                now);
        templateRepository.save(copy);
        attachMaterial(copy.id(), copiedMaterial);
        return detail(copy);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TrainingTemplateSummaryView> list(
            CurrentUser user, String role, String level, String status, int page, int size) {
        TrainingOwnerRole ownerRole = resolveRole(user, role);
        TrainingLevel parsedLevel =
                level == null || level.isBlank() ? null : TrainingLevel.fromWire(level);
        TrainingTemplateStatus parsedStatus = status == null || status.isBlank()
                ? TrainingTemplateStatus.ACTIVE : TrainingTemplateStatus.fromWire(status);
        PageResult<TrainingTemplate> result = templateRepository.listByOwner(
                user.accountId().value(), ownerRole, parsedLevel, parsedStatus, page, size);
        List<TrainingTemplateSummaryView> items = result.items().stream()
                .map(this::summary)
                .toList();
        return new PageResult<>(items, result.total(), result.page(), result.size());
    }

    @Override
    @Transactional(readOnly = true)
    public TrainingTemplateDetailView get(CurrentUser user, UUID templateId) {
        return detail(requireOwnedActive(user, templateId));
    }

    @Override
    @Transactional
    public TrainingTemplateDetailView update(
            CurrentUser user, UUID templateId, UpdateTrainingTemplateCommand command) {
        TrainingTemplate template = requireOwnedActive(user, templateId);
        boolean configChange = command.trainingLevel() != null
                || command.topic() != null
                || command.textbookVersion() != null
                || command.recommendedSeconds() != null
                || command.maxSeconds() != null
                || command.weights() != null
                || command.virtualStudents() != null
                || command.materialId() != null
                || command.removeMaterial();
        if (configChange
                && template.ownerRole() == TrainingOwnerRole.TEACHER
                && publicationRepository.countPublishedByTemplate(template.id()) > 0) {
            throw new TrainingException(TrainingError.TRAINING_TEMPLATE_LOCKED,
                    "模板发布中，配置已锁定，请先下架",
                    Map.of("template_id", template.id().toString()));
        }
        if (!configChange) {
            if (command.name() != null) {
                template.rename(command.name(), clock.now());
                templateRepository.save(template);
            }
            return detail(template);
        }

        TrainingConfigSnapshot current = template.config();
        TrainingLevel level = command.trainingLevel() == null
                ? template.trainingLevel() : TrainingLevel.fromWire(command.trainingLevel());
        TrainingMaterial oldMaterial = materialRepository
                .listAttachedByTemplate(template.id()).stream().findFirst().orElse(null);
        TrainingMaterial newMaterial = command.materialId() == null
                ? null : requireUploadedTemplateMaterial(
                        command.materialId(), user.accountId().value());
        TrainingReferenceMaterial reference = current.referenceMaterial();
        if (newMaterial != null && (oldMaterial == null
                || !oldMaterial.id().equals(newMaterial.id()))) {
            reference = configFactory.toReference(newMaterial);
            detachMaterial(oldMaterial);
        } else if (command.removeMaterial() && newMaterial == null) {
            reference = null;
            detachMaterial(oldMaterial);
        }

        List<TrainingVirtualStudent> students = command.virtualStudents() == null
                ? current.virtualStudents()
                : configFactory.toDomain(command.virtualStudents());
        TrainingConfigSnapshot config = configFactory.snapshot(
                level,
                command.topic() == null ? current.topic() : command.topic(),
                command.textbookVersion() == null
                        ? current.textbookVersion() : command.textbookVersion(),
                command.recommendedSeconds() == null
                        ? current.duration().recommendedSeconds() : command.recommendedSeconds(),
                command.maxSeconds() == null
                        ? current.duration().maxSeconds() : command.maxSeconds(),
                command.weights() == null ? current.weights() : command.weights(),
                students,
                reference);
        template.updateConfig(
                command.name() == null ? template.name() : command.name(),
                level,
                config,
                clock.now());
        templateRepository.save(template);
        attachMaterial(template.id(), newMaterial);
        return detail(template);
    }

    @Override
    @Transactional
    public void delete(CurrentUser user, UUID templateId) {
        TrainingTemplate template = templateRepository
                .findByIdAndOwner(templateId, user.accountId().value())
                .orElseThrow(() -> templateNotFound(templateId));
        if (!template.isActive()) {
            return;
        }
        lifecycle.archiveAndClean(template, clock.now());
    }

    @Override
    @Transactional(readOnly = true)
    public TaskTemplateConfigView taskConfig(CurrentUser user, UUID templateId) {
        access.requireTeacher(user);
        TrainingTemplate template = templateRepository
                .findByIdAndOwner(templateId, user.accountId().value())
                .filter(TrainingTemplate::isActive)
                .filter(value -> value.ownerRole() == TrainingOwnerRole.TEACHER)
                .orElseThrow(() -> templateNotFound(templateId));
        TrainingConfigSnapshot config = template.config();
        return new TaskTemplateConfigView(
                template.id(),
                template.name(),
                template.trainingLevel(),
                config.topic(),
                config.textbookVersion(),
                config.duration(),
                config.weights(),
                config.virtualStudents(),
                config.referenceMaterial());
    }

    private TrainingTemplate createWithinQuota(
            UUID ownerId, java.util.function.Supplier<TrainingTemplate> factory) {
        templateRepository.lockOwner(ownerId);
        if (templateRepository.countActiveByOwner(ownerId)
                >= TrainingDefaults.MAX_ACTIVE_TEMPLATES) {
            throw limitReached();
        }
        return factory.get();
    }

    private TrainingTemplate requireOwnedActive(CurrentUser user, UUID templateId) {
        return templateRepository
                .findByIdAndOwner(templateId, user.accountId().value())
                .filter(TrainingTemplate::isActive)
                .orElseThrow(() -> templateNotFound(templateId));
    }

    private TrainingMaterial requireUploadedTemplateMaterial(
            UUID materialId, UUID ownerId) {
        TrainingMaterial material = materialRepository
                .findByIdAndOwner(materialId, ownerId)
                .orElseThrow(() -> materialInvalid(materialId));
        if (material.usage() != TrainingMaterialUsage.TEMPLATE
                || material.status() != TrainingMaterialStatus.UPLOADED) {
            throw materialInvalid(materialId);
        }
        return material;
    }

    private void attachMaterial(UUID templateId, TrainingMaterial material) {
        if (material == null) {
            return;
        }
        material.attachTo(templateId);
        materialRepository.save(material);
    }

    private void detachMaterial(TrainingMaterial material) {
        if (material == null) {
            return;
        }
        try {
            materialStorage.delete(material.objectKey());
        } catch (RuntimeException exception) {
            log.warn("Failed to delete replaced template material {}", material.id(), exception);
        }
        material.markDeleted();
        materialRepository.save(material);
    }

    private TrainingMaterial copyMaterial(
            UUID schoolId,
            UUID ownerId,
            TrainingReferenceMaterial source,
            Instant now) {
        byte[] bytes = materialObjectReader.read(source.objectKey())
                .orElseThrow(() -> new TrainingException(
                        TrainingError.TRAINING_TEMPLATE_COPY_FAILED,
                        "参考材料复制失败", Map.of("field", "reference_material")));
        UUID materialId = idGenerator.nextId();
        String objectKey = TEMPLATE_OBJECT_PREFIX + schoolId + "/" + ownerId + "/"
                + materialId + "." + extension(source.filename());
        try {
            materialStorage.put(objectKey, bytes, source.mime());
        } catch (RuntimeException exception) {
            throw new TrainingException(TrainingError.TRAINING_TEMPLATE_COPY_FAILED,
                    "参考材料复制失败", Map.of("field", "reference_material"));
        }
        return TrainingMaterial.create(
                materialId,
                ownerId,
                TrainingMaterialUsage.TEMPLATE,
                source.filename(),
                source.mime(),
                bytes.length,
                objectKey,
                source.parsedText(),
                now,
                now.plus(24, ChronoUnit.HOURS));
    }

    private void requireVisibleCourse(
            CurrentUser user,
            TrainingTemplate template,
            UUID courseId,
            Instant now) {
        CourseInfo course = courseDirectoryPort.findCourse(courseId)
                .orElseThrow(() -> new TrainingException(
                        TrainingError.TRAINING_TEMPLATE_UNAVAILABLE,
                        "目标课程不存在", Map.of("course_id", courseId.toString())));
        if (!course.schoolId().equals(user.schoolId().value())
                || !course.schoolId().equals(template.schoolId())) {
            throw new TrainingException(TrainingError.CROSS_SCHOOL_ACCESS,
                    "不能跨校复制模板", Map.of());
        }
        if (!course.active() || (course.endAt() != null && !course.endAt().isAfter(now))) {
            throw new TrainingException(TrainingError.TRAINING_PUBLICATION_UNAVAILABLE,
                    "目标课程不可用", Map.of("course_id", courseId.toString()));
        }
        if (!courseDirectoryPort.isActiveStudent(courseId, user.accountId().value())) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "不在目标课程学生名单", Map.of("course_id", courseId.toString()));
        }
    }

    private TrainingOwnerRole resolveRole(CurrentUser user, String ownerRole) {
        TrainingOwnerRole accountRole = switch (user.type()) {
            case TEACHER -> TrainingOwnerRole.TEACHER;
            case STUDENT -> TrainingOwnerRole.STUDENT;
            default -> throw new TrainingException(TrainingError.ROLE_NOT_ALLOWED,
                    "仅教师或学生可管理个人模板", Map.of());
        };
        if (ownerRole == null || ownerRole.isBlank()) {
            return accountRole;
        }
        TrainingOwnerRole requested = TrainingOwnerRole.fromWire(ownerRole);
        if (requested != accountRole) {
            throw new TrainingException(TrainingError.TRAINING_TEMPLATE_INVALID,
                    "角色不属于当前账号", Map.of("field", "owner_role"));
        }
        return requested;
    }

    private TrainingTemplateSummaryView summary(TrainingTemplate template) {
        TrainingConfigSnapshot config = template.config();
        return new TrainingTemplateSummaryView(
                template.id(),
                template.ownerRole(),
                template.name(),
                template.trainingLevel(),
                config.topic(),
                template.status(),
                template.sourceType(),
                publicationRepository.countPublishedByTemplate(template.id()),
                config.duration().maxSeconds(),
                config.virtualStudents().size(),
                template.updatedAt());
    }

    private TrainingTemplateDetailView detail(TrainingTemplate template) {
        TrainingConfigSnapshot config = template.config();
        long publishedCount = publicationRepository.countPublishedByTemplate(template.id());
        TrainingReferenceMaterial reference = config.referenceMaterial();
        return new TrainingTemplateDetailView(
                template.id(),
                template.schoolId(),
                template.ownerRole(),
                template.name(),
                template.trainingLevel(),
                config.topic(),
                config.textbookVersion(),
                config.duration(),
                config.weights(),
                config.virtualStudents(),
                template.status().wireValue(),
                template.sourceType(),
                template.sourceTemplateId(),
                template.sourcePublicationId(),
                reference == null ? null : new TrainingMaterialRefView(
                        reference.filename(), reference.mime(), reference.size(),
                        reference.charCount()),
                publishedCount,
                template.ownerRole() == TrainingOwnerRole.TEACHER && publishedCount > 0,
                template.createdAt(),
                template.updatedAt());
    }

    private TrainingLevel requireLevel(String level) {
        if (level == null || level.isBlank()) {
            throw new TrainingException(TrainingError.TRAINING_TEMPLATE_INVALID,
                    "训练档位必填", Map.of("field", "training_level"));
        }
        return TrainingLevel.fromWire(level);
    }

    private static String defaultCopyName(String sourceName) {
        String candidate = sourceName + COPY_SUFFIX;
        if (candidate.length() <= TrainingDefaults.MAX_NAME_LENGTH) {
            return candidate;
        }
        int keep = TrainingDefaults.MAX_NAME_LENGTH - COPY_SUFFIX.length();
        return sourceName.substring(0, Math.max(1, keep)) + COPY_SUFFIX;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "bin";
        }
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.length() > 10 ? "bin" : extension;
    }

    private TrainingException templateNotFound(UUID templateId) {
        return new TrainingException(TrainingError.TRAINING_TEMPLATE_NOT_FOUND,
                "模板不存在", Map.of("template_id", templateId.toString()));
    }

    private TrainingException publicationNotFound(UUID publicationId) {
        return new TrainingException(TrainingError.TRAINING_PUBLICATION_NOT_FOUND,
                "发布不存在", Map.of("publication_id", publicationId.toString()));
    }

    private TrainingException materialInvalid(UUID materialId) {
        return new TrainingException(TrainingError.TRAINING_MATERIAL_INVALID,
                "参考材料不可用", Map.of("material_id", materialId.toString()));
    }

    private TrainingException limitReached() {
        return new TrainingException(TrainingError.TRAINING_TEMPLATE_LIMIT,
                "活动模板已达上限 30 个", Map.of("limit", TrainingDefaults.MAX_ACTIVE_TEMPLATES));
    }
}
