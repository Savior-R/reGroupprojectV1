package com.shijiangtai.m3classroom.domain.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.shared.domain.SessionId;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrameTypeClassifierTest {
    private final FrameTypeClassifier classifier = new FrameTypeClassifier();

    @Test
    void nullConfidenceUsesChangeScore() {
        assertThat(classifier.classify(null, new BigDecimal("0.5")))
                .isEqualTo(FrameType.SLIDE_CHANGE);
        assertThat(classifier.classify(null, new BigDecimal("0.1249")))
                .isEqualTo(FrameType.NORMAL);
        assertThat(classifier.classify(null, null)).isEqualTo(FrameType.NORMAL);
    }

    @Test
    void lowConfidenceWinsOverSlideChange() {
        assertThat(classifier.classify(new BigDecimal("0.59"), new BigDecimal("0.9")))
                .isEqualTo(FrameType.LOW_CONFIDENCE);
    }

    @Test
    void thresholdBoundaries() {
        assertThat(classifier.classify(new BigDecimal("0.60"), new BigDecimal("0.125")))
                .isEqualTo(FrameType.SLIDE_CHANGE);
        assertThat(classifier.classify(new BigDecimal("0.60"), new BigDecimal("0.1249")))
                .isEqualTo(FrameType.NORMAL);
    }

    @Test
    void pendingFrameStartsWithEmptyPayload() {
        SessionId sessionId = new SessionId(UUID.randomUUID());
        SessionFrame frame = SessionFrame.pending(
                UUID.randomUUID(), sessionId, 1200, new BigDecimal("0.3"), "k", "hash");
        assertThat(frame.status()).isEqualTo(FrameStatus.PENDING);
        assertThat(frame.frameType()).isEqualTo(FrameType.NORMAL);
        assertThat(frame.boundingBoxes()).isEmpty();
        assertThat(frame.text()).isNull();
    }
}
