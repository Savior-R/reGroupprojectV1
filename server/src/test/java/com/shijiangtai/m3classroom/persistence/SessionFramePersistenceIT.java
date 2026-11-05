package com.shijiangtai.m3classroom.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.application.port.SessionFrameStore;
import com.shijiangtai.m3classroom.domain.session.FrameStatus;
import com.shijiangtai.m3classroom.domain.session.FrameType;
import com.shijiangtai.m3classroom.domain.session.OcrBox;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionFrame;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionFramePersistenceIT extends PostgresIntegrationTest {
    @Autowired
    private SessionFrameStore store;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void roundTripsPendingAndProcessedFrame() {
        SessionId id = savePreparingSession();
        UUID frameId = UUID.randomUUID();
        store.save(SessionFrame.pending(frameId, id, 1200, new BigDecimal("0.20"),
                "m3/frames/" + id.value() + "/" + frameId + ".jpg", "hash"));
        SessionFrame pending = store.find(frameId).orElseThrow();
        assertThat(pending.status()).isEqualTo(FrameStatus.PENDING);
        assertThat(pending.changeScore()).isEqualByComparingTo("0.20");
        assertThat(pending.boundingBoxes()).isEmpty();

        List<OcrBox> boxes = List.of(new OcrBox("标题", 1.0, 2.0, 3.0, 4.0, new BigDecimal("0.88")));
        store.markProcessed(frameId, "识别文本", boxes, new BigDecimal("0.88"),
                FrameType.LOW_CONFIDENCE, pending.objectKey());

        SessionFrame processed = store.find(frameId).orElseThrow();
        assertThat(processed.status()).isEqualTo(FrameStatus.PROCESSED);
        assertThat(processed.text()).isEqualTo("识别文本");
        assertThat(processed.boundingBoxes()).containsExactlyElementsOf(boxes);
        assertThat(processed.frameType()).isEqualTo(FrameType.LOW_CONFIDENCE);
        assertThat(processed.objectKey()).isEqualTo(pending.objectKey());
    }

    @Test
    void markFailedAfterProcessedKeepsProcessedFrame() {
        SessionId id = savePreparingSession();
        UUID frameId = UUID.randomUUID();
        store.save(SessionFrame.pending(frameId, id, 1200, new BigDecimal("0.20"), "key", "hash"));
        store.markProcessed(frameId, "识别文本", List.of(), new BigDecimal("0.90"),
                FrameType.NORMAL, "key");

        store.markFailed(frameId, "late event failure");

        SessionFrame frame = store.find(frameId).orElseThrow();
        assertThat(frame.status()).isEqualTo(FrameStatus.PROCESSED);
        assertThat(frame.text()).isEqualTo("识别文本");
        assertThat(frame.confidence()).isEqualByComparingTo("0.90");
        assertThat(frame.errorMessage()).isNull();
    }

    @Test
    void markFailedStoresTruncatedError() {
        SessionId id = savePreparingSession();
        UUID frameId = UUID.randomUUID();
        store.save(SessionFrame.pending(frameId, id, 1200, new BigDecimal("0.20"), "key", "hash"));

        String longMessage = "x".repeat(600);
        store.markFailed(frameId, longMessage);

        SessionFrame failed = store.find(frameId).orElseThrow();
        assertThat(failed.status()).isEqualTo(FrameStatus.FAILED);
        assertThat(failed.errorMessage()).hasSize(500).isEqualTo(longMessage.substring(0, 500));
    }

    @Test
    void unknownFrameUpdatesAreNoOps() {
        UUID unknown = UUID.randomUUID();

        assertThatCode(() -> store.markProcessed(unknown, "text", List.of(),
                new BigDecimal("0.90"), FrameType.NORMAL, "key"))
                .doesNotThrowAnyException();
        assertThatCode(() -> store.markFailed(unknown, "ocr timeout"))
                .doesNotThrowAnyException();
        assertThat(store.find(unknown)).isEmpty();
    }

    private SessionId savePreparingSession() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO m3_classroom.tasks
                    (id, course_id, mode, topic, duration_recommended_seconds,
                     duration_max_seconds, created_by, status)
                VALUES (?, ?, 'free', '荷塘月色', 900, 1200, ?, 'published')
                """, taskId.value(), DevDataConfig.COURSE_ID, DevDataConfig.TEACHER_ID);
        SessionId sessionId = new SessionId(UUID.randomUUID());
        sessions.save(Session.createPreparing(
                sessionId, SessionType.TASK, taskId, TestFixtures.COURSE, TestFixtures.STUDENT,
                TaskMode.FREE, 1, TestFixtures.NOW));
        return sessionId;
    }
}
