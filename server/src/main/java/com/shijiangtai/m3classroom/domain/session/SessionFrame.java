package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.shared.domain.SessionId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SessionFrame(
        UUID id,
        SessionId sessionId,
        long timestampMs,
        String text,
        List<OcrBox> boundingBoxes,
        BigDecimal confidence,
        BigDecimal changeScore,
        FrameType frameType,
        String objectKey,
        String contentHash,
        FrameStatus status,
        String errorMessage) {

    public SessionFrame {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        if (timestampMs < 0) {
            throw new IllegalArgumentException("timestampMs must not be negative");
        }
        boundingBoxes = List.copyOf(Objects.requireNonNull(boundingBoxes, "boundingBoxes"));
        Objects.requireNonNull(frameType, "frameType");
        Objects.requireNonNull(status, "status");
    }

    public static SessionFrame pending(
            UUID id,
            SessionId sessionId,
            long timestampMs,
            BigDecimal changeScore,
            String objectKey,
            String contentHash) {
        return new SessionFrame(
                id,
                sessionId,
                timestampMs,
                null,
                List.of(),
                null,
                changeScore,
                FrameType.NORMAL,
                objectKey,
                contentHash,
                FrameStatus.PENDING,
                null);
    }
}
