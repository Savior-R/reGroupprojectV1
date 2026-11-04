package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.application.port.MaterialExtractionException;
import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import com.shijiangtai.m3classroom.application.view.MaterialView;
import com.shijiangtai.m3classroom.application.view.TaskViewMapper;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.ReferenceMaterial;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.TaskId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TaskMaterialServiceImpl implements TaskMaterialService {
    private static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final int MIN_CHARS = 20;
    private static final int MAX_CHARS = 100_000;

    private final TaskRepository taskRepository;
    private final CourseQueryPort courseQueryPort;
    private final TaskAccessPolicy accessPolicy;
    private final MaterialTextExtractor textExtractor;
    private final MaterialObjectStorage objectStorage;
    private final TaskMaterialTxService txService;
    private final IdGenerator idGenerator;

    public TaskMaterialServiceImpl(
            TaskRepository taskRepository,
            CourseQueryPort courseQueryPort,
            TaskAccessPolicy accessPolicy,
            MaterialTextExtractor textExtractor,
            MaterialObjectStorage objectStorage,
            TaskMaterialTxService txService,
            IdGenerator idGenerator) {
        this.taskRepository = taskRepository;
        this.courseQueryPort = courseQueryPort;
        this.accessPolicy = accessPolicy;
        this.textExtractor = textExtractor;
        this.objectStorage = objectStorage;
        this.txService = txService;
        this.idGenerator = idGenerator;
    }

    @Override
    public MaterialView upload(
            CurrentUser teacher, TaskId taskId, String filename, String mime, byte[] content) {
        Task task = findTask(taskId);
        CourseInfo course = accessPolicy.requireTeacherTask(teacher, task);
        task.assertDraft();
        if (task.draft().mode() == TaskMode.INTERVIEW) {
            throw new DomainException(ErrorCode.MATERIAL_NOT_APPLICABLE,
                    "结构化面试不需要参考材料");
        }
        String extension = extension(filename);
        if (extension == null) {
            throw new DomainException(ErrorCode.MATERIAL_TYPE_UNSUPPORTED,
                    "不支持的参考材料格式", Map.of("filename", filename));
        }
        if (content == null || content.length == 0) {
            throw new DomainException(ErrorCode.MATERIAL_PARSE_FAILED, "参考材料为空");
        }
        if (content.length > MAX_SIZE) {
            throw new DomainException(ErrorCode.MATERIAL_TOO_LARGE,
                    "参考材料超过 20 MB", Map.of("size", content.length));
        }
        String text;
        try {
            text = textExtractor.extract(content, filename, mime);
        } catch (MaterialExtractionException exception) {
            throw mapExtractionError(exception);
        }
        if (text == null || text.length() < MIN_CHARS) {
            throw new DomainException(ErrorCode.MATERIAL_PARSE_FAILED,
                    "参考材料可提取文字少于 20 字");
        }
        if (text.length() > MAX_CHARS) {
            throw new DomainException(ErrorCode.MATERIAL_TOO_LARGE,
                    "参考材料解析文本超过 100,000 字", Map.of("char_count", text.length()));
        }
        String contentType = mime == null || mime.isBlank()
                ? "application/octet-stream" : mime;
        UUID materialId = idGenerator.nextId();
        String objectKey = "materials/%s/%s/%s/%s.%s".formatted(
                course.schoolId(), course.id(), taskId.value(), materialId, extension);
        objectStorage.put(objectKey, content, contentType);
        ReferenceMaterial material = new ReferenceMaterial(
                materialId, filename, objectKey, contentType, content.length, text, text.length());
        ReferenceMaterial old;
        try {
            old = txService.attach(taskId, material);
        } catch (RuntimeException exception) {
            safeDelete(objectKey);
            throw exception;
        }
        if (old != null) {
            safeDelete(old.objectKey());
        }
        return TaskViewMapper.toMaterialView(material, false);
    }

    @Override
    public MaterialView get(CurrentUser teacher, TaskId taskId) {
        Task task = findTask(taskId);
        accessPolicy.requireTeacherTask(teacher, task);
        ReferenceMaterial material = task.referenceMaterial();
        if (material == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                    "参考材料不存在", Map.of("task_id", taskId.value()));
        }
        return TaskViewMapper.toMaterialView(material, true);
    }

    @Override
    public void delete(CurrentUser teacher, TaskId taskId) {
        Task task = findTask(taskId);
        accessPolicy.requireTeacherTask(teacher, task);
        ReferenceMaterial old = txService.clear(taskId);
        if (old != null) {
            safeDelete(old.objectKey());
        }
    }

    private Task findTask(TaskId taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "任务不存在", Map.of("task_id", taskId.value())));
    }

    private DomainException mapExtractionError(MaterialExtractionException exception) {
        return switch (exception.error()) {
            case UNSUPPORTED_TYPE -> new DomainException(
                    ErrorCode.MATERIAL_TYPE_UNSUPPORTED, exception.getMessage());
            case TOO_LARGE -> new DomainException(
                    ErrorCode.MATERIAL_TOO_LARGE, exception.getMessage());
            case NO_TEXT, TOO_SHORT, CORRUPT -> new DomainException(
                    ErrorCode.MATERIAL_PARSE_FAILED, exception.getMessage());
        };
    }

    private String extension(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "pdf", "docx", "txt", "md" -> extension;
            default -> null;
        };
    }

    private void safeDelete(String objectKey) {
        try {
            objectStorage.delete(objectKey);
        } catch (RuntimeException ignored) {
            // Best effort: an orphaned object is preferable to failing the request.
        }
    }
}
