package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.command.PublishTrainingTemplateCommand;
import com.shijiangtai.m6training.application.view.StudentTemplateCardView;
import com.shijiangtai.m6training.application.view.StudentTemplateDetailView;
import com.shijiangtai.m6training.application.view.TrainingPublicationView;
import com.shijiangtai.m6training.domain.TrainingConfigSnapshot;
import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import com.shijiangtai.m6training.domain.TrainingLevel;
import com.shijiangtai.m6training.domain.TrainingOwnerRole;
import com.shijiangtai.m6training.domain.TrainingPublicationStatus;
import com.shijiangtai.m6training.domain.TrainingTemplate;
import com.shijiangtai.m6training.domain.TrainingTemplatePublication;
import com.shijiangtai.m6training.domain.TrainingTemplatePublicationRepository;
import com.shijiangtai.m6training.domain.TrainingTemplatePublicationRepository.PublicationRow;
import com.shijiangtai.m6training.domain.TrainingTemplateRepository;
import com.shijiangtai.m6training.domain.TrainingUnpublishReason;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort;
import com.shijiangtai.m6training.domain.port.CourseDirectoryPort.CourseInfo;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrainingTemplatePublicationServiceImpl
        implements TrainingTemplatePublicationService {
    private final TrainingTemplateRepository templateRepository;
    private final TrainingTemplatePublicationRepository publicationRepository;
    private final TrainingTemplateLifecycle lifecycle;
    private final CourseDirectoryPort courseDirectoryPort;
    private final M6Access access;
    private final IdGenerator idGenerator;
    private final ClockProvider clock;

    public TrainingTemplatePublicationServiceImpl(
            TrainingTemplateRepository templateRepository,
            TrainingTemplatePublicationRepository publicationRepository,
            TrainingTemplateLifecycle lifecycle,
            CourseDirectoryPort courseDirectoryPort,
            M6Access access,
            IdGenerator idGenerator,
            ClockProvider clock) {
        this.templateRepository = templateRepository;
        this.publicationRepository = publicationRepository;
        this.lifecycle = lifecycle;
        this.courseDirectoryPort = courseDirectoryPort;
        this.access = access;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TrainingPublicationView publish(
            CurrentUser user, UUID templateId, PublishTrainingTemplateCommand command) {
        access.requireTeacher(user);
        UUID teacherId = user.accountId().value();
        if (command == null || command.courseId() == null) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "course_id 必填", Map.of("field", "course_id"));
        }
        TrainingTemplate template = templateRepository.findByIdAndOwner(templateId, teacherId)
                .orElseThrow(() -> templateNotFound(templateId));
        if (!template.isActive() || template.ownerRole() != TrainingOwnerRole.TEACHER) {
            throw new TrainingException(TrainingError.TRAINING_TEMPLATE_UNAVAILABLE,
                    "模板不可发布", Map.of("template_id", templateId.toString()));
        }
        Instant now = clock.now();
        CourseInfo course = courseDirectoryPort.findCourse(command.courseId())
                .orElseThrow(() -> new TrainingException(
                        TrainingError.TRAINING_NOT_ELIGIBLE,
                        "目标课程不存在", Map.of("course_id", command.courseId().toString())));
        if (!course.schoolId().equals(user.schoolId().value())
                || !course.schoolId().equals(template.schoolId())) {
            throw new TrainingException(TrainingError.CROSS_SCHOOL_ACCESS,
                    "不能跨校发布模板", Map.of());
        }
        if (!course.active() || (course.endAt() != null && !course.endAt().isAfter(now))) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "目标课程不可用", Map.of("course_id", course.id().toString()));
        }
        if (!courseDirectoryPort.isActiveTeacher(course.id(), teacherId)) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "不在目标课程老师组", Map.of("course_id", course.id().toString()));
        }
        if (publicationRepository.findPublished(template.id(), course.id()).isPresent()) {
            throw publicationExists();
        }
        TrainingTemplatePublication publication = TrainingTemplatePublication.publish(
                idGenerator.nextId(), template.id(), course.id(), teacherId, now);
        try {
            publicationRepository.save(publication);
        } catch (DataIntegrityViolationException exception) {
            throw publicationExists();
        }
        TrainingConfigSnapshot config = template.config();
        return new TrainingPublicationView(
                publication.id(),
                template.id(),
                template.name(),
                template.trainingLevel(),
                config.topic(),
                template.sourceType(),
                course.id(),
                course.name(),
                publication.status().wireValue(),
                publication.publishedAt(),
                publication.unpublishedAt(),
                null,
                config.duration().maxSeconds(),
                config.virtualStudents().size());
    }

    @Override
    @Transactional
    public void unpublish(CurrentUser user, UUID publicationId) {
        access.requireTeacher(user);
        TrainingTemplatePublication publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> publicationNotFound(publicationId));
        TrainingTemplate template = templateRepository.findById(publication.templateId())
                .orElseThrow(() -> publicationNotFound(publicationId));
        if (!template.isOwnedBy(user.accountId().value())) {
            throw new TrainingException(TrainingError.FORBIDDEN,
                    "只能下架自己的发布", Map.of("publication_id", publicationId.toString()));
        }
        if (!publication.isPublished()) {
            return;
        }
        publication.unpublish(TrainingUnpublishReason.MANUAL, clock.now());
        publicationRepository.save(publication);
        lifecycle.cancelPreparingSessions(publication.templateId(), clock.now());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TrainingPublicationView> listMine(
            CurrentUser user, UUID templateId, UUID courseId, String status, int page, int size) {
        access.requireTeacher(user);
        TrainingPublicationStatus parsedStatus =
                status == null || status.isBlank()
                        ? null : TrainingPublicationStatus.fromWire(status);
        PageResult<PublicationRow> rows = publicationRepository.listByOwner(
                user.accountId().value(), templateId, courseId, parsedStatus, page, size);
        List<TrainingPublicationView> items = rows.items().stream()
                .map(this::toView)
                .toList();
        return new PageResult<>(items, rows.total(), rows.page(), rows.size());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StudentTemplateCardView> listForStudent(
            CurrentUser user, String level, UUID courseId, int page, int size) {
        access.requireStudent(user);
        TrainingLevel parsedLevel =
                level == null || level.isBlank() ? null : TrainingLevel.fromWire(level);
        PageResult<PublicationRow> rows = publicationRepository.listStudentVisible(
                user.accountId().value(),
                user.schoolId().value(),
                parsedLevel,
                courseId,
                page,
                size);
        List<StudentTemplateCardView> items = rows.items().stream()
                .map(row -> new StudentTemplateCardView(
                        row.publicationId(),
                        row.templateName(),
                        row.topic(),
                        row.courseId(),
                        row.courseName(),
                        row.trainingLevel(),
                        row.maxDurationSeconds() * 1000L,
                        row.virtualStudentCount(),
                        row.copied()))
                .toList();
        return new PageResult<>(items, rows.total(), rows.page(), rows.size());
    }

    @Override
    @Transactional(readOnly = true)
    public StudentTemplateDetailView getForStudent(CurrentUser user, UUID publicationId) {
        access.requireStudent(user);
        TrainingTemplatePublication publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> publicationNotFound(publicationId));
        if (!publication.isPublished()) {
            throw new TrainingException(TrainingError.TRAINING_PUBLICATION_UNAVAILABLE,
                    "发布已下架", Map.of("publication_id", publicationId.toString()));
        }
        TrainingTemplate template = templateRepository.findById(publication.templateId())
                .filter(TrainingTemplate::isActive)
                .filter(value -> value.ownerRole() == TrainingOwnerRole.TEACHER)
                .orElseThrow(() -> new TrainingException(
                        TrainingError.TRAINING_TEMPLATE_UNAVAILABLE,
                        "模板已删除或不可用",
                        Map.of("publication_id", publicationId.toString())));
        Instant now = clock.now();
        CourseInfo course = courseDirectoryPort.findCourse(publication.courseId())
                .orElseThrow(() -> new TrainingException(
                        TrainingError.TRAINING_PUBLICATION_UNAVAILABLE,
                        "目标课程不存在",
                        Map.of("course_id", publication.courseId().toString())));
        if (!course.schoolId().equals(user.schoolId().value())
                || !course.schoolId().equals(template.schoolId())) {
            throw new TrainingException(TrainingError.CROSS_SCHOOL_ACCESS,
                    "不能跨校访问模板", Map.of());
        }
        if (!course.active() || (course.endAt() != null && !course.endAt().isAfter(now))) {
            throw new TrainingException(TrainingError.TRAINING_PUBLICATION_UNAVAILABLE,
                    "目标课程不可用", Map.of("course_id", course.id().toString()));
        }
        if (!courseDirectoryPort.isActiveStudent(course.id(), user.accountId().value())) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "不在目标课程学生名单", Map.of("course_id", course.id().toString()));
        }
        TrainingConfigSnapshot config = template.config();
        return new StudentTemplateDetailView(
                publication.id(),
                template.name(),
                config.topic(),
                course.id(),
                course.name(),
                template.trainingLevel(),
                config.duration().recommendedMillis(),
                config.duration().maxMillis(),
                List.copyOf(config.weights().keySet()),
                config.virtualStudents(),
                templateRepository.existsActiveCopy(
                        user.accountId().value(), publication.id()));
    }

    private TrainingPublicationView toView(PublicationRow row) {
        return new TrainingPublicationView(
                row.publicationId(),
                row.templateId(),
                row.templateName(),
                row.trainingLevel(),
                row.topic(),
                row.sourceType(),
                row.courseId(),
                row.courseName(),
                row.status().wireValue(),
                row.publishedAt(),
                row.unpublishedAt(),
                row.unpublishReason() == null ? null : row.unpublishReason().wireValue(),
                row.maxDurationSeconds(),
                row.virtualStudentCount());
    }

    private TrainingException templateNotFound(UUID templateId) {
        return new TrainingException(TrainingError.TRAINING_TEMPLATE_NOT_FOUND,
                "模板不存在", Map.of("template_id", templateId.toString()));
    }

    private TrainingException publicationNotFound(UUID publicationId) {
        return new TrainingException(TrainingError.TRAINING_PUBLICATION_NOT_FOUND,
                "发布不存在", Map.of("publication_id", publicationId.toString()));
    }

    private TrainingException publicationExists() {
        return new TrainingException(TrainingError.TRAINING_PUBLICATION_EXISTS,
                "该课程已发布此模板", Map.of());
    }
}
