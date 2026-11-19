package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.command.MaterialUpload;
import com.shijiangtai.m3classroom.application.port.MaterialExtractionException;
import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.ReferenceMaterial;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.TaskId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Validates, parses and stores teacher reference material for a task. */
@Component
public class ReferenceMaterialFactory {
    private static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final int MIN_CHARS = 20;
    private static final int MAX_CHARS = 100_000;

    private final MaterialTextExtractor textExtractor;
    private final MaterialObjectStorage objectStorage;
    private final IdGenerator idGenerator;

    public ReferenceMaterialFactory(
            MaterialTextExtractor textExtractor,
            MaterialObjectStorage objectStorage,
            IdGenerator idGenerator) {
        this.textExtractor = Objects.requireNonNull(textExtractor, "textExtractor");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    public ReferenceMaterial create(CourseInfo course, TaskId taskId, MaterialUpload upload) {
        Objects.requireNonNull(course, "course");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(upload, "upload");
        String extension = extension(upload.filename());
        if (extension == null) {
            throw new DomainException(ErrorCode.MATERIAL_TYPE_UNSUPPORTED,
                    "不支持的参考材料格式", Map.of("filename", upload.filename()));
        }
        if (upload.content().length == 0) {
            throw new DomainException(ErrorCode.MATERIAL_PARSE_FAILED, "参考材料为空");
        }
        if (upload.content().length > MAX_SIZE) {
            throw new DomainException(ErrorCode.MATERIAL_TOO_LARGE,
                    "参考材料超过 20 MB", Map.of("size", upload.content().length));
        }
        String text;
        try {
            text = textExtractor.extract(upload.content(), upload.filename(), upload.mime());
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
        String contentType = upload.mime() == null || upload.mime().isBlank()
                ? "application/octet-stream" : upload.mime();
        UUID materialId = idGenerator.nextId();
        String objectKey = "materials/%s/%s/%s/%s.%s".formatted(
                course.schoolId(), course.id(), taskId.value(), materialId, extension);
        objectStorage.put(objectKey, upload.content(), contentType);
        return new ReferenceMaterial(
                materialId, upload.filename(), objectKey, contentType,
                upload.content().length, text, text.length());
    }

    public void deleteQuietly(ReferenceMaterial material) {
        if (material == null) {
            return;
        }
        try {
            objectStorage.delete(material.objectKey());
        } catch (RuntimeException ignored) {
            // Best effort: an orphaned object is preferable to failing the request.
        }
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
}
