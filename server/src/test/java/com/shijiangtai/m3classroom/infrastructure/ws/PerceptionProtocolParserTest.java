package com.shijiangtai.m3classroom.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.infrastructure.ws.PerceptionProtocolParser.AudioStart;
import com.shijiangtai.m3classroom.infrastructure.ws.PerceptionProtocolParser.AudioStop;
import com.shijiangtai.m3classroom.infrastructure.ws.PerceptionProtocolParser.Message;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import org.junit.jupiter.api.Test;

/** Wire parsing for the perception WebSocket (05A §6.2). */
class PerceptionProtocolParserTest {

    @Test
    void parsesValidAudioStart() {
        Message message = PerceptionProtocolParser.parse(
                "{\"type\":\"audio.start\",\"format\":\"pcm16\","
                        + "\"sample_rate\":16000,\"channels\":1}");

        assertThat(message).isEqualTo(new AudioStart(16000, "pcm16", 1));
        assertThat(((AudioStart) message).format()).isEqualTo("pcm16");
    }

    @Test
    void parsesValidAudioStop() {
        assertThat(PerceptionProtocolParser.parse("{\"type\":\"audio.stop\"}"))
                .isEqualTo(new AudioStop());
    }

    @Test
    void rejectsMissingType() {
        assertValidationError(
                "{\"format\":\"pcm16\",\"sample_rate\":16000,\"channels\":1}");
    }

    @Test
    void rejectsUnsupportedFormat() {
        assertValidationError(
                "{\"type\":\"audio.start\",\"format\":\"mp3\","
                        + "\"sample_rate\":16000,\"channels\":1}");
    }

    @Test
    void rejectsUnsupportedSampleRate() {
        assertValidationError(
                "{\"type\":\"audio.start\",\"format\":\"pcm16\","
                        + "\"sample_rate\":8000,\"channels\":1}");
    }

    @Test
    void rejectsUnsupportedChannelCount() {
        assertValidationError(
                "{\"type\":\"audio.start\",\"format\":\"pcm16\","
                        + "\"sample_rate\":16000,\"channels\":2}");
    }

    @Test
    void rejectsMalformedJson() {
        assertValidationError("{not-json");
    }

    @Test
    void rejectsUnknownMessageType() {
        assertValidationError("{\"type\":\"audio.pause\"}");
    }

    private static void assertValidationError(String json) {
        assertThatThrownBy(() -> PerceptionProtocolParser.parse(json))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
