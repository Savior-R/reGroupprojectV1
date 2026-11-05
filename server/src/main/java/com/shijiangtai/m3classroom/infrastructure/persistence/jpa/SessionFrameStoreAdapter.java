package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.application.port.SessionFrameStore;
import com.shijiangtai.m3classroom.domain.session.FrameStatus;
import com.shijiangtai.m3classroom.domain.session.FrameType;
import com.shijiangtai.m3classroom.domain.session.OcrBox;
import com.shijiangtai.m3classroom.domain.session.SessionFrame;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Frame store backed by {@code m3_classroom.frames}.
 *
 * <p>{@code bounding_boxes} is persisted as a jsonb array of OCR boxes. The entity keeps it as a
 * JSON string (the datasource uses {@code stringtype=unspecified}), matching the existing
 * {@code SessionEventEntity} jsonb mapping. {@code created_at} is populated by the column default;
 * {@code updated_at} is always written explicitly because PostgreSQL defaults do not re-apply on
 * updates.
 */
@Repository
public class SessionFrameStoreAdapter implements SessionFrameStore {
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    private final SessionFrameJpaRepository repository;
    private final ObjectMapper objectMapper;

    public SessionFrameStoreAdapter(
            SessionFrameJpaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void save(SessionFrame frame) {
        SessionFrameEntity entity = new SessionFrameEntity();
        entity.setId(frame.id());
        entity.setSessionId(frame.sessionId().value());
        entity.setTimestampMs(frame.timestampMs());
        entity.setText(frame.text());
        entity.setBoundingBoxes(writeBoxes(frame.boundingBoxes()));
        entity.setConfidence(frame.confidence());
        entity.setChangeScore(frame.changeScore());
        entity.setFrameType(frame.frameType().wireValue());
        entity.setObjectKey(frame.objectKey());
        entity.setContentHash(frame.contentHash());
        entity.setStatus(frame.status().wireValue());
        entity.setErrorMessage(frame.errorMessage());
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
    }

    @Override
    public Optional<SessionFrame> find(UUID frameId) {
        return repository.findById(frameId).map(this::toDomain);
    }

    @Override
    @Transactional
    public void markProcessed(
            UUID frameId,
            String text,
            List<OcrBox> boxes,
            BigDecimal confidence,
            FrameType frameType,
            String objectKey) {
        repository.findById(frameId).ifPresent(entity -> {
            entity.setText(text);
            entity.setBoundingBoxes(writeBoxes(boxes));
            entity.setConfidence(confidence);
            entity.setFrameType(frameType.wireValue());
            entity.setObjectKey(objectKey);
            entity.setStatus(FrameStatus.PROCESSED.wireValue());
            entity.setErrorMessage(null);
            entity.setUpdatedAt(Instant.now());
        });
    }

    @Override
    @Transactional
    public void markFailed(UUID frameId, String errorMessage) {
        repository.findById(frameId).ifPresent(entity -> {
            entity.setStatus(FrameStatus.FAILED.wireValue());
            entity.setErrorMessage(truncate(errorMessage));
            entity.setUpdatedAt(Instant.now());
        });
    }

    private SessionFrame toDomain(SessionFrameEntity entity) {
        return new SessionFrame(
                entity.getId(),
                new SessionId(entity.getSessionId()),
                entity.getTimestampMs(),
                entity.getText(),
                readBoxes(entity.getBoundingBoxes()),
                entity.getConfidence(),
                entity.getChangeScore(),
                frameType(entity.getFrameType()),
                entity.getObjectKey(),
                entity.getContentHash(),
                frameStatus(entity.getStatus()),
                entity.getErrorMessage());
    }

    private String writeBoxes(List<OcrBox> boxes) {
        try {
            return objectMapper.writeValueAsString(boxes);
        } catch (Exception exception) {
            throw codecError("感知帧 JSON 编码失败", exception);
        }
    }

    private List<OcrBox> readBoxes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            OcrBox[] boxes = objectMapper.readValue(json, OcrBox[].class);
            return boxes == null ? List.of() : List.of(boxes);
        } catch (Exception exception) {
            throw codecError("感知帧 JSON 解码失败", exception);
        }
    }

    private static FrameType frameType(String wireValue) {
        for (FrameType type : FrameType.values()) {
            if (type.wireValue().equals(wireValue)) {
                return type;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR, "感知帧类型非法",
                Map.of("frame_type", String.valueOf(wireValue)));
    }

    private static FrameStatus frameStatus(String wireValue) {
        for (FrameStatus status : FrameStatus.values()) {
            if (status.wireValue().equals(wireValue)) {
                return status;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR, "感知帧状态非法",
                Map.of("status", String.valueOf(wireValue)));
    }

    private static String truncate(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return errorMessage;
        }
        return errorMessage.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    private static DomainException codecError(String message, Exception exception) {
        return new DomainException(ErrorCode.INTERNAL_ERROR, message,
                Map.of("cause", exception.getClass().getSimpleName()));
    }
}
