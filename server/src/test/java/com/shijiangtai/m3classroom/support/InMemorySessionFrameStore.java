package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.SessionFrameStore;
import com.shijiangtai.m3classroom.domain.session.FrameStatus;
import com.shijiangtai.m3classroom.domain.session.FrameType;
import com.shijiangtai.m3classroom.domain.session.OcrBox;
import com.shijiangtai.m3classroom.domain.session.SessionFrame;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory {@link SessionFrameStore} fake; threads may write while tests poll. */
public class InMemorySessionFrameStore implements SessionFrameStore {

    private final Map<UUID, SessionFrame> frames = new ConcurrentHashMap<>();
    private final AtomicInteger markFailedCalls = new AtomicInteger();

    @Override
    public void save(SessionFrame frame) {
        frames.put(frame.id(), frame);
    }

    @Override
    public Optional<SessionFrame> find(UUID frameId) {
        return Optional.ofNullable(frames.get(frameId));
    }

    @Override
    public void markProcessed(
            UUID frameId,
            String text,
            List<OcrBox> boxes,
            BigDecimal confidence,
            FrameType frameType,
            String objectKey) {
        frames.computeIfPresent(frameId, (id, frame) -> new SessionFrame(
                id,
                frame.sessionId(),
                frame.timestampMs(),
                text,
                boxes == null ? List.of() : boxes,
                confidence,
                frame.changeScore(),
                frameType,
                objectKey,
                frame.contentHash(),
                FrameStatus.PROCESSED,
                null));
    }

    @Override
    public void markFailed(UUID frameId, String errorMessage) {
        markFailedCalls.incrementAndGet();
        // Mirrors the production adapter: only a pending frame can fail.
        frames.computeIfPresent(frameId, (id, frame) -> frame.status() == FrameStatus.PENDING
                ? new SessionFrame(
                        id,
                        frame.sessionId(),
                        frame.timestampMs(),
                        frame.text(),
                        frame.boundingBoxes(),
                        frame.confidence(),
                        frame.changeScore(),
                        frame.frameType(),
                        frame.objectKey(),
                        frame.contentHash(),
                        FrameStatus.FAILED,
                        errorMessage)
                : frame);
    }

    public int size() {
        return frames.size();
    }

    public int markFailedCalls() {
        return markFailedCalls.get();
    }
}
