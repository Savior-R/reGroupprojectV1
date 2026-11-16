package com.shijiangtai.m6training.application;

import com.shijiangtai.m3classroom.application.port.MaterialExtractionError;
import com.shijiangtai.m3classroom.application.port.MaterialExtractionException;
import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import com.shijiangtai.m6training.application.view.TrainingMaterialView;
import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import com.shijiangtai.m6training.domain.TrainingMaterial;
import com.shijiangtai.m6training.domain.TrainingMaterialRepository;
import com.shijiangtai.m6training.domain.TrainingMaterialStatus;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TrainingMaterialServiceImpl implements TrainingMaterialService {
    private static final String OBJECT_PREFIX = "training-materials/";
    private static final int MAX_FILENAME_LENGTH = 255;

    private final TrainingMaterialRepository materialRepository;
    private final MaterialTextExtractor textExtractor;
    private final MaterialObjectStorage materialStorage;
    private final M6Access access;
    private final IdGenerator idGenerator;
    private final ClockProvider clock;

    public TrainingMaterialServiceImpl(
            TrainingMaterialRepository materialRepository,
            MaterialTextExtractor textExtractor,
            MaterialObjectStorage materialStorage,
            M6Access access,
            IdGenerator idGenerator,
            ClockProvider clock) {
        this.materialRepository = materialRepository;
        this.textExtractor = textExtractor;
        this.materialStorage = materialStorage;
        this.access = access;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TrainingMaterialView upload(CurrentUser user, MultipartFile file) {
        access.requireStudent(user);
        if (file == null || file.isEmpty()) {
            throw new TrainingException(TrainingError.TRAINING_MATERIAL_INVALID,
                    "材料为空", Map.of("field", "file"));
        }
        String filename = sanitizeFilename(file.getOriginalFilename());
        if (!textExtractor.supports(filename)) {
            throw new TrainingException(TrainingError.MATERIAL_TYPE_UNSUPPORTED,
                    "仅支持 PDF / DOCX / TXT / MD", Map.of("field", "file"));
        }
        if (file.getSize() > TrainingMaterial.MAX_SIZE_BYTES) {
            throw new TrainingException(TrainingError.MATERIAL_TOO_LARGE,
                    "材料超过 20 MB", Map.of("field", "file"));
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new TrainingException(TrainingError.MATERIAL_PARSE_FAILED,
                    "材料读取失败", Map.of("field", "file"));
        }
        String mime = file.getContentType() == null
                ? "application/octet-stream" : file.getContentType();
        String parsedText = extract(content, filename, mime);
        if (parsedText == null || parsedText.isBlank()) {
            throw new TrainingException(TrainingError.MATERIAL_PARSE_FAILED,
                    "材料未解析出文本", Map.of("field", "file"));
        }

        UUID materialId = idGenerator.nextId();
        String objectKey = OBJECT_PREFIX
                + user.schoolId().value() + "/"
                + user.accountId().value() + "/"
                + materialId + "." + extension(filename);
        try {
            materialStorage.put(objectKey, content, mime);
        } catch (RuntimeException exception) {
            throw new TrainingException(TrainingError.SERVICE_UNAVAILABLE,
                    "材料存储不可用", Map.of("field", "file"));
        }

        Instant now = clock.now();
        TrainingMaterial material = TrainingMaterial.create(
                materialId,
                user.accountId().value(),
                filename,
                mime,
                content.length,
                objectKey,
                parsedText,
                now,
                now.plus(24, ChronoUnit.HOURS));
        materialRepository.save(material);
        return new TrainingMaterialView(
                material.id(),
                material.filename(),
                material.charCount(),
                material.preview(),
                material.expiresAt());
    }

    @Override
    @Transactional
    public void delete(CurrentUser user, UUID materialId) {
        access.requireStudent(user);
        TrainingMaterial material = materialRepository
                .findByIdAndStudent(materialId, user.accountId().value())
                .orElseThrow(() -> new TrainingException(
                        TrainingError.TRAINING_MATERIAL_INVALID,
                        "材料不存在", Map.of("material_id", materialId.toString())));
        if (material.status() != TrainingMaterialStatus.UPLOADED) {
            throw new TrainingException(TrainingError.TRAINING_MATERIAL_INVALID,
                    "只能删除未使用的材料", Map.of("material_id", materialId.toString()));
        }
        try {
            materialStorage.delete(material.objectKey());
        } catch (RuntimeException exception) {
            throw new TrainingException(TrainingError.SERVICE_UNAVAILABLE,
                    "材料存储不可用", Map.of("material_id", materialId.toString()));
        }
        material.markDeleted();
        materialRepository.save(material);
    }

    private String extract(byte[] content, String filename, String mime) {
        try {
            return textExtractor.extract(content, filename, mime);
        } catch (MaterialExtractionException exception) {
            TrainingError error = switch (exception.error()) {
                case UNSUPPORTED_TYPE -> TrainingError.MATERIAL_TYPE_UNSUPPORTED;
                case TOO_LARGE -> TrainingError.MATERIAL_TOO_LARGE;
                case NO_TEXT, TOO_SHORT, CORRUPT -> TrainingError.MATERIAL_PARSE_FAILED;
            };
            throw new TrainingException(error, "材料解析失败", Map.of("field", "file"));
        } catch (RuntimeException exception) {
            throw new TrainingException(TrainingError.MATERIAL_PARSE_FAILED,
                    "材料解析失败", Map.of("field", "file"));
        }
    }

    private static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            throw new TrainingException(TrainingError.TRAINING_MATERIAL_INVALID,
                    "缺少文件名", Map.of("field", "file"));
        }
        String normalized = original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) {
            throw new TrainingException(TrainingError.TRAINING_MATERIAL_INVALID,
                    "缺少文件名", Map.of("field", "file"));
        }
        return name.length() <= MAX_FILENAME_LENGTH
                ? name : name.substring(name.length() - MAX_FILENAME_LENGTH);
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "bin";
        }
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.length() > 10 ? "bin" : extension;
    }
}
