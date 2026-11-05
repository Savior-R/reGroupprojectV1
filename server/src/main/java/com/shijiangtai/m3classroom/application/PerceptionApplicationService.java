package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import com.shijiangtai.m3classroom.application.port.OcrClient;
import com.shijiangtai.m3classroom.application.port.OcrResult;
import com.shijiangtai.m3classroom.application.port.SessionFrameStore;
import com.shijiangtai.m3classroom.application.view.PerceptionFrameView;
import com.shijiangtai.m3classroom.domain.session.FrameStatus;
import com.shijiangtai.m3classroom.domain.session.FrameType;
import com.shijiangtai.m3classroom.domain.session.FrameTypeClassifier;
import com.shijiangtai.m3classroom.domain.session.OcrBox;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionFrame;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Screenshot ingest and asynchronous OCR (05A §6.2, 02 §4.3).
 *
 * <p>The frame row is persisted as {@code pending} before the request returns; OCR runs on a
 * bounded single-thread daemon executor and either marks the frame {@code processed} and emits
 * {@code perception.screen_changed}, or marks it {@code failed}. Failures in the async path never
 * propagate to the request thread and never change the session state.
 */
@Service
public class PerceptionApplicationService {

    private static final Logger LOG = LoggerFactory.getLogger(PerceptionApplicationService.class);
    private static final String JPEG_CONTENT_TYPE = "image/jpeg";
    private static final long MAX_JPEG_BYTES = 5L * 1024 * 1024;
    private static final int OCR_QUEUE_CAPACITY = 32;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final SessionRepository sessionRepository;
    private final SessionAccessPolicy sessionAccessPolicy;
    private final SessionFrameStore frameStore;
    private final OcrClient ocrClient;
    private final MaterialObjectStorage objectStorage;
    private final SessionRuntimeRegistry runtimeRegistry;
    private final IdGenerator idGenerator;
    private final FrameTypeClassifier frameTypeClassifier;
    private final ExecutorService ocrExecutor;
    private final boolean ownsExecutor;

    @Autowired
    public PerceptionApplicationService(
            SessionRepository sessionRepository,
            SessionAccessPolicy sessionAccessPolicy,
            SessionFrameStore frameStore,
            OcrClient ocrClient,
            MaterialObjectStorage objectStorage,
            SessionRuntimeRegistry runtimeRegistry,
            IdGenerator idGenerator) {
        this(sessionRepository, sessionAccessPolicy, frameStore, ocrClient, objectStorage,
                runtimeRegistry, idGenerator, newOcrExecutor(), true);
    }

    /** Test seam: the caller owns and shuts down the executor. */
    PerceptionApplicationService(
            SessionRepository sessionRepository,
            SessionAccessPolicy sessionAccessPolicy,
            SessionFrameStore frameStore,
            OcrClient ocrClient,
            MaterialObjectStorage objectStorage,
            SessionRuntimeRegistry runtimeRegistry,
            IdGenerator idGenerator,
            ExecutorService ocrExecutor) {
        this(sessionRepository, sessionAccessPolicy, frameStore, ocrClient, objectStorage,
                runtimeRegistry, idGenerator, ocrExecutor, false);
    }

    private PerceptionApplicationService(
            SessionRepository sessionRepository,
            SessionAccessPolicy sessionAccessPolicy,
            SessionFrameStore frameStore,
            OcrClient ocrClient,
            MaterialObjectStorage objectStorage,
            SessionRuntimeRegistry runtimeRegistry,
            IdGenerator idGenerator,
            ExecutorService ocrExecutor,
            boolean ownsExecutor) {
        this.sessionRepository = Objects.requireNonNull(
                sessionRepository, "sessionRepository");
        this.sessionAccessPolicy = Objects.requireNonNull(
                sessionAccessPolicy, "sessionAccessPolicy");
        this.frameStore = Objects.requireNonNull(frameStore, "frameStore");
        this.ocrClient = Objects.requireNonNull(ocrClient, "ocrClient");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.frameTypeClassifier = new FrameTypeClassifier();
        this.ocrExecutor = Objects.requireNonNull(ocrExecutor, "ocrExecutor");
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Persists a screenshot as a {@code pending} frame and queues OCR.
     *
     * <p>Validation, ownership and session-state failures are raised synchronously; OCR and
     * storage failures only degrade the frame.
     */
    public PerceptionFrameView ingestScreen(
            CurrentUser user,
            SessionId sessionId,
            byte[] jpeg,
            String contentType,
            long atMs,
            BigDecimal changeScore) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(sessionId, "sessionId");
        validate(jpeg, contentType, atMs, changeScore);

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "会话不存在", Map.of("session_id", sessionId.value().toString())));
        sessionAccessPolicy.requireOwner(user, session);
        if (session.status() != SessionStatus.IN_PROGRESS) {
            throw new DomainException(ErrorCode.SESSION_STATE_INVALID,
                    "当前状态不允许上传截图",
                    Map.of("status", session.status().wireValue(),
                            "action", "perception.screen"));
        }

        UUID frameId = idGenerator.nextId();
        String objectKey = frameObjectKey(sessionId, frameId);
        String storedObjectKey = storeObject(objectKey, jpeg);
        frameStore.save(SessionFrame.pending(
                frameId, sessionId, atMs, changeScore, storedObjectKey, null));
        submitOcr(frameId, sessionId, jpeg, atMs, changeScore, storedObjectKey);
        return new PerceptionFrameView(frameId, FrameStatus.PENDING.wireValue());
    }

    private static void validate(
            byte[] jpeg, String contentType, long atMs, BigDecimal changeScore) {
        if (jpeg == null || jpeg.length == 0) {
            throw validationError("截图文件为空", "field", "file");
        }
        if (contentType == null || !JPEG_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
            throw validationError("截图仅支持 image/jpeg", "content_type",
                    String.valueOf(contentType));
        }
        if (jpeg.length > MAX_JPEG_BYTES) {
            throw validationError("截图超过 5 MB", "size", jpeg.length);
        }
        if (atMs < 0) {
            throw validationError("截图时间必须大于等于 0", "at_ms", atMs);
        }
        if (changeScore != null
                && (changeScore.compareTo(ZERO) < 0 || changeScore.compareTo(ONE) > 0)) {
            throw validationError("屏幕变化分数必须在 0 到 1 之间", "change_score", changeScore);
        }
    }

    private static DomainException validationError(
            String message, String field, Object value) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message,
                Map.of("field", field, "value", value));
    }

    private String storeObject(String objectKey, byte[] jpeg) {
        try {
            objectStorage.put(objectKey, jpeg, JPEG_CONTENT_TYPE);
            return objectKey;
        } catch (RuntimeException exception) {
            LOG.warn("Failed to store screen frame {}; continuing without the object: {}",
                    objectKey, exception.toString());
            return null;
        }
    }

    private void submitOcr(
            UUID frameId,
            SessionId sessionId,
            byte[] jpeg,
            long atMs,
            BigDecimal changeScore,
            String objectKey) {
        try {
            ocrExecutor.execute(() -> processFrame(
                    frameId, sessionId, jpeg, atMs, changeScore, objectKey));
        } catch (RejectedExecutionException exception) {
            LOG.warn("OCR queue full for session {} frame {}", sessionId.value(), frameId);
            frameStore.markFailed(frameId, "OCR 队列已满");
        }
    }

    private void processFrame(
            UUID frameId,
            SessionId sessionId,
            byte[] jpeg,
            long atMs,
            BigDecimal changeScore,
            String objectKey) {
        OcrResult result;
        FrameType frameType;
        try {
            result = ocrClient.recognize(jpeg);
            frameType = frameTypeClassifier.classify(result.confidence(), changeScore);
        } catch (RuntimeException exception) {
            LOG.warn("OCR failed for session {} frame {}: {}",
                    sessionId.value(), frameId, exception.getMessage(), exception);
            markFailedQuietly(frameId, exception.getMessage());
            return;
        }

        try {
            List<OcrBox> boxes = result.boxes() == null ? List.of() : result.boxes();
            frameStore.markProcessed(
                    frameId, result.text(), boxes, result.confidence(), frameType, objectKey);
        } catch (RuntimeException exception) {
            LOG.warn("Failed to persist OCR result for session {} frame {}: {}",
                    sessionId.value(), frameId, exception.getMessage(), exception);
            markFailedQuietly(frameId, exception.getMessage());
            return;
        }

        // Event emission is downstream of the persisted frame: a failure here must not mark a
        // processed frame as failed, otherwise a stored perception event and a failed frame
        // row could coexist.
        try {
            runtimeRegistry.find(sessionId).ifPresent(actor ->
                    actor.onScreenProcessed(frameId, atMs, frameType.wireValue()));
        } catch (RuntimeException exception) {
            LOG.warn("Failed to emit screen event for session {} frame {}: {}",
                    sessionId.value(), frameId, exception.getMessage(), exception);
        }
    }

    private void markFailedQuietly(UUID frameId, String errorMessage) {
        try {
            frameStore.markFailed(frameId,
                    errorMessage == null ? "OCR 处理失败" : errorMessage);
        } catch (RuntimeException exception) {
            LOG.warn("Failed to mark frame {} as failed: {}",
                    frameId, exception.toString());
        }
    }

    private static String frameObjectKey(SessionId sessionId, UUID frameId) {
        return "m3/frames/%s/%s.jpg".formatted(sessionId.value(), frameId);
    }

    private static ExecutorService newOcrExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(OCR_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "perception-ocr");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @PreDestroy
    public void shutdown() {
        if (ownsExecutor) {
            ocrExecutor.shutdown();
        }
    }
}
