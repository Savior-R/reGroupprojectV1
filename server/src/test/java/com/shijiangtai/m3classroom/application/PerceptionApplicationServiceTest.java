package com.shijiangtai.m3classroom.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import com.shijiangtai.m3classroom.application.port.OcrResult;
import com.shijiangtai.m3classroom.application.view.PerceptionFrameView;
import com.shijiangtai.m3classroom.domain.session.FrameStatus;
import com.shijiangtai.m3classroom.domain.session.FrameType;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionFrame;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.m3classroom.support.FakeMaterialObjectStorage;
import com.shijiangtai.m3classroom.support.FakeOcrClient;
import com.shijiangtai.m3classroom.support.InMemorySessionFrameStore;
import com.shijiangtai.m3classroom.support.InMemorySessionRepository;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PerceptionApplicationServiceTest {

    private static final long AT_MS = 1000L;
    private static final BigDecimal CHANGE_SCORE = new BigDecimal("0.30");
    private static final BigDecimal HIGH_CONFIDENCE = new BigDecimal("0.90");

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionFrameStore frames = new InMemorySessionFrameStore();
    private final FakeOcrClient ocr = new FakeOcrClient();
    private final FakeCourseQueryPort courses = TestFixtures.coursePort();
    private final SessionAccessPolicy accessPolicy = new SessionAccessPolicy(courses);

    private ExecutorService executor;
    private FakeMaterialObjectStorage storage;
    private SessionRuntimeRegistry runtimeRegistry;
    private SessionActor actor;
    private PerceptionApplicationService service;
    private SessionId sessionId;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "perception-ocr-test");
            thread.setDaemon(true);
            return thread;
        });
        storage = new FakeMaterialObjectStorage();
        runtimeRegistry = mock(SessionRuntimeRegistry.class);
        actor = mock(SessionActor.class);
        when(runtimeRegistry.find(any(SessionId.class))).thenReturn(Optional.of(actor));
        service = newService(storage);
        Session session = inProgressSession();
        sessionId = session.id();
        sessions.save(session);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        ocr.release();
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void ingestSavesPendingFrameAndReturnsPendingView() throws Exception {
        ocr.block();

        PerceptionFrameView view = service.ingestScreen(
                TestFixtures.student(), sessionId, jpeg(), "image/jpeg", AT_MS, CHANGE_SCORE);

        SessionFrame pending = frames.find(view.frameId()).orElseThrow();
        assertThat(pending.status()).isEqualTo(FrameStatus.PENDING);
        assertThat(pending.sessionId()).isEqualTo(sessionId);
        assertThat(pending.timestampMs()).isEqualTo(AT_MS);
        assertThat(pending.changeScore()).isEqualByComparingTo(CHANGE_SCORE);
        assertThat(pending.objectKey())
                .isEqualTo("m3/frames/" + sessionId.value() + "/" + view.frameId() + ".jpg");
        assertThat(view.status()).isEqualTo("pending");

        ocr.release();
        awaitAsync();
    }

    @Test
    void ocrSuccessMarksProcessedAndEmitsScreenChangedEvent() throws Exception {
        ocr.succeedWith(new OcrResult(
                "板书标题", List.of(), HIGH_CONFIDENCE, "fake-ocr"));

        PerceptionFrameView view = service.ingestScreen(
                TestFixtures.student(), sessionId, jpeg(), "image/jpeg", AT_MS, CHANGE_SCORE);
        awaitAsync();

        SessionFrame processed = frames.find(view.frameId()).orElseThrow();
        assertThat(processed.status()).isEqualTo(FrameStatus.PROCESSED);
        assertThat(processed.text()).isEqualTo("板书标题");
        assertThat(processed.confidence()).isEqualByComparingTo(HIGH_CONFIDENCE);
        assertThat(processed.frameType()).isEqualTo(FrameType.SLIDE_CHANGE);
        verify(actor).onScreenProcessed(view.frameId(), AT_MS, "slide_change");
    }

    @Test
    void actorFailureAfterOcrSuccessKeepsFrameProcessed() throws Exception {
        ocr.succeedWith(new OcrResult(
                "板书标题", List.of(), HIGH_CONFIDENCE, "fake-ocr"));
        doThrow(new RuntimeException("事件写入失败")).when(actor)
                .onScreenProcessed(any(), anyLong(), anyString());

        PerceptionFrameView view = service.ingestScreen(
                TestFixtures.student(), sessionId, jpeg(), "image/jpeg", AT_MS, CHANGE_SCORE);
        awaitAsync();

        SessionFrame processed = frames.find(view.frameId()).orElseThrow();
        assertThat(processed.status()).isEqualTo(FrameStatus.PROCESSED);
        assertThat(processed.text()).isEqualTo("板书标题");
        assertThat(processed.confidence()).isEqualByComparingTo(HIGH_CONFIDENCE);
        assertThat(processed.frameType()).isEqualTo(FrameType.SLIDE_CHANGE);
        assertThat(processed.errorMessage()).isNull();
        assertThat(frames.markFailedCalls()).isZero();
    }

    @Test
    void ocrFailureMarksFailedAndDoesNotThrow() throws Exception {
        ocr.failWith(new RuntimeException("OCR 服务不可用"));

        PerceptionFrameView view = service.ingestScreen(
                TestFixtures.student(), sessionId, jpeg(), "image/jpeg", AT_MS, CHANGE_SCORE);
        awaitAsync();

        SessionFrame failed = frames.find(view.frameId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(FrameStatus.FAILED);
        assertThat(failed.errorMessage()).contains("OCR 服务不可用");
        verify(actor, never()).onScreenProcessed(any(), anyLong(), anyString());
    }

    @Test
    void storageFailureStillOcrProcesses() throws Exception {
        MaterialObjectStorage failingStorage = new FakeMaterialObjectStorage() {
            @Override
            public void put(String objectKey, byte[] content, String contentType) {
                throw new IllegalStateException("MinIO 不可用");
            }
        };
        PerceptionApplicationService failingService = newService(failingStorage);

        PerceptionFrameView view = failingService.ingestScreen(
                TestFixtures.student(), sessionId, jpeg(), "image/jpeg", AT_MS, CHANGE_SCORE);
        awaitAsync();

        SessionFrame processed = frames.find(view.frameId()).orElseThrow();
        assertThat(processed.objectKey()).isNull();
        assertThat(processed.status()).isEqualTo(FrameStatus.PROCESSED);
        verify(actor).onScreenProcessed(view.frameId(), AT_MS, "slide_change");
    }

    @Test
    void nonOwnerIsForbidden() {
        assertThatThrownBy(() -> service.ingestScreen(
                TestFixtures.student2(), sessionId, jpeg(), "image/jpeg", AT_MS, CHANGE_SCORE))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(frames.size()).isZero();
    }

    @Test
    void nonInProgressSessionRejected() {
        Session preparing = Session.createPreparing(
                new SessionId(UUID.randomUUID()),
                SessionType.TASK,
                new TaskId(UUID.randomUUID()),
                TestFixtures.COURSE,
                TestFixtures.STUDENT,
                TaskMode.FREE,
                1,
                TestFixtures.NOW);
        sessions.save(preparing);

        assertThatThrownBy(() -> service.ingestScreen(
                TestFixtures.student(), preparing.id(), jpeg(), "image/jpeg", AT_MS, CHANGE_SCORE))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SESSION_STATE_INVALID));
        assertThat(frames.size()).isZero();
    }

    @Test
    void invalidParamsRejected() {
        assertValidationError(new byte[0], "image/jpeg", AT_MS, null);
        assertValidationError(jpeg(), null, AT_MS, null);
        assertValidationError(jpeg(), "image/png", AT_MS, null);
        assertValidationError(new byte[5 * 1024 * 1024 + 1], "image/jpeg", AT_MS, null);
        assertValidationError(jpeg(), "image/jpeg", -1, null);
        assertValidationError(jpeg(), "image/jpeg", AT_MS, new BigDecimal("1.1"));
        assertValidationError(jpeg(), "image/jpeg", AT_MS, new BigDecimal("-0.1"));
        assertThat(frames.size()).isZero();
    }

    private void assertValidationError(
            byte[] content, String contentType, long atMs, BigDecimal changeScore) {
        assertThatThrownBy(() -> service.ingestScreen(
                TestFixtures.student(), sessionId, content, contentType, atMs, changeScore))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private PerceptionApplicationService newService(MaterialObjectStorage objectStorage) {
        return new PerceptionApplicationService(
                sessions,
                accessPolicy,
                frames,
                ocr,
                objectStorage,
                runtimeRegistry,
                UUID::randomUUID,
                executor);
    }

    private Session inProgressSession() {
        Session session = Session.createPreparing(
                new SessionId(UUID.randomUUID()),
                SessionType.TASK,
                new TaskId(UUID.randomUUID()),
                TestFixtures.COURSE,
                TestFixtures.STUDENT,
                TaskMode.FREE,
                1,
                TestFixtures.NOW);
        session.start(TestFixtures.NOW.plusSeconds(1));
        return session;
    }

    private void awaitAsync() throws InterruptedException {
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private static byte[] jpeg() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};
    }
}
