package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.m3classroom.domain.session.FrameType;
import com.shijiangtai.m3classroom.domain.session.OcrBox;
import com.shijiangtai.m3classroom.domain.session.SessionFrame;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable screen-frame records (05A perception, 01 §"frames"). */
public interface SessionFrameStore {

    void save(SessionFrame frame);

    Optional<SessionFrame> find(UUID frameId);

    void markProcessed(
            UUID frameId,
            String text,
            List<OcrBox> boxes,
            BigDecimal confidence,
            FrameType frameType,
            String objectKey);

    /** Marks a still-pending frame failed; a processed frame is never downgraded. */
    void markFailed(UUID frameId, String errorMessage);
}
