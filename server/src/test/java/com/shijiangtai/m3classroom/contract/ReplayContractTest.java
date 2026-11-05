package com.shijiangtai.m3classroom.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.application.view.ReplayStreamView;
import com.shijiangtai.m3classroom.application.view.ReplayView;
import com.shijiangtai.m3classroom.application.view.SessionEventView;
import com.shijiangtai.m3classroom.application.view.SessionTextView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire contract for replay, event and text views (05B §5–§7, 02 §4.3).
 *
 * <p>The replay manifest is exactly
 * {@code {session_id,mode,duration_ms,streams:[{stream_type,url,offset_ms,duration_ms,mime}]}}.
 * Event and text fixtures freeze the keys consumed by the browser replay timeline.
 */
class ReplayContractTest {

    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000008a1");
    private static final UUID EVENT_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000008a2");
    private static final UUID TEXT_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000008a3");
    private static final UUID SPEAKER_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000008a4");

    private static final String SCREEN_URL =
            "http://localhost:9000/shijiangtai/recordings/school/task/session/screen.webm"
                    + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=900"
                    + "&X-Amz-Credential=minioadmin%2F20261105%2Fus-east-1%2Fs3%2Faws4_request"
                    + "&X-Amz-Signature=signature";
    private static final String CAMERA_URL =
            "http://localhost:9000/shijiangtai/recordings/school/task/session/camera.webm"
                    + "?X-Amz-Signature=signature";
    private static final String AUDIO_URL =
            "http://localhost:9000/shijiangtai/recordings/school/task/session/audio.webm"
                    + "?X-Amz-Signature=signature";

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void replayViewFreezesManifestAndStreamKeys() {
        ReplayView view = new ReplayView(SESSION_ID, "free", 1_200_000L, List.of(
                new ReplayStreamView("screen", SCREEN_URL, 0L, 1_200_000L, "video/webm"),
                new ReplayStreamView("camera", CAMERA_URL, 120L, 1_198_000L, "video/webm"),
                new ReplayStreamView("audio", AUDIO_URL, 0L, 1_200_000L, "audio/webm")));

        JsonNode json = tree(view);

        assertThat(json.propertyNames())
                .containsExactlyInAnyOrder("session_id", "mode", "duration_ms", "streams");
        assertThat(json.get("session_id").asString()).isEqualTo(SESSION_ID.toString());
        assertThat(json.get("mode").asString()).isEqualTo("free");
        assertThat(json.get("duration_ms").asLong()).isEqualTo(1_200_000L);
        assertThat(json.has("sessionId")).isFalse();
        assertThat(json.has("durationMs")).isFalse();

        JsonNode streams = json.get("streams");
        assertThat(streams.isArray()).isTrue();
        assertThat(streams.size()).isEqualTo(3);

        assertThat(streams.get(0).propertyNames()).containsExactlyInAnyOrder(
                "stream_type", "url", "offset_ms", "duration_ms", "mime");
        assertThat(streams.get(0).get("stream_type").asString()).isEqualTo("screen");
        assertThat(streams.get(0).get("url").asString()).isEqualTo(SCREEN_URL);
        assertThat(streams.get(0).get("offset_ms").asLong()).isZero();
        assertThat(streams.get(0).get("duration_ms").asLong()).isEqualTo(1_200_000L);
        assertThat(streams.get(0).get("mime").asString()).isEqualTo("video/webm");
        assertThat(streams.get(0).has("streamType")).isFalse();
        assertThat(streams.get(0).has("offsetMs")).isFalse();

        assertThat(streams.get(1).get("stream_type").asString()).isEqualTo("camera");
        assertThat(streams.get(1).get("offset_ms").asLong()).isEqualTo(120L);
        assertThat(streams.get(1).get("duration_ms").asLong()).isEqualTo(1_198_000L);
        assertThat(streams.get(2).get("stream_type").asString()).isEqualTo("audio");
        assertThat(streams.get(2).get("mime").asString()).isEqualTo("audio/webm");
    }

    @Test
    void sessionEventViewFreezesTimelineEntryKeys() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text_id", TEXT_ID.toString());
        payload.put("count", 2);

        SessionEventView view = new SessionEventView(
                EVENT_ID, 2L, "teacher.speech", 1_000L, payload);

        JsonNode json = tree(view);

        assertThat(json.propertyNames())
                .containsExactlyInAnyOrder("event_id", "seq", "type", "at_ms", "payload");
        assertThat(json.get("event_id").asString()).isEqualTo(EVENT_ID.toString());
        assertThat(json.get("seq").asLong()).isEqualTo(2L);
        assertThat(json.get("type").asString()).isEqualTo("teacher.speech");
        assertThat(json.get("at_ms").asLong()).isEqualTo(1_000L);
        assertThat(json.get("payload").propertyNames())
                .containsExactlyInAnyOrder("text_id", "count");
        assertThat(json.get("payload").get("text_id").asString())
                .isEqualTo(TEXT_ID.toString());
        assertThat(json.get("payload").get("count").asInt()).isEqualTo(2);
        assertThat(json.has("eventId")).isFalse();
        assertThat(json.has("atMs")).isFalse();
    }

    @Test
    void sessionTextViewFreezesUnifiedTranscriptKeys() {
        SessionTextView view = new SessionTextView(
                TEXT_ID, "teacher", SPEAKER_ID, "asr", "utterance", "同学们好",
                0L, 1_000L, 500L, new BigDecimal("0.95"), "final");

        JsonNode json = tree(view);

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "id", "speaker_type", "speaker_ref", "source", "kind", "text",
                "start_ms", "end_ms", "at_ms", "confidence", "status");
        assertThat(json.get("id").asString()).isEqualTo(TEXT_ID.toString());
        assertThat(json.get("speaker_type").asString()).isEqualTo("teacher");
        assertThat(json.get("speaker_ref").asString()).isEqualTo(SPEAKER_ID.toString());
        assertThat(json.get("source").asString()).isEqualTo("asr");
        assertThat(json.get("kind").asString()).isEqualTo("utterance");
        assertThat(json.get("text").asString()).isEqualTo("同学们好");
        assertThat(json.get("start_ms").asLong()).isZero();
        assertThat(json.get("end_ms").asLong()).isEqualTo(1_000L);
        assertThat(json.get("at_ms").asLong()).isEqualTo(500L);
        assertThat(json.get("confidence").asDouble()).isEqualTo(0.95);
        assertThat(json.get("status").asString()).isEqualTo("final");
        assertThat(json.has("speakerType")).isFalse();
        assertThat(json.has("startMs")).isFalse();
    }

    @Test
    void textViewKeepsNullableAuditFieldsAsContractKeys() {
        SessionTextView view = new SessionTextView(
                TEXT_ID, "student", null, "manual", "note", "老师好",
                null, null, 2_000L, null, "draft");

        JsonNode json = tree(view);

        List<String> keys = new ArrayList<>();
        json.propertyNames().forEach(keys::add);
        assertThat(keys).containsExactlyInAnyOrder(
                "id", "speaker_type", "speaker_ref", "source", "kind", "text",
                "start_ms", "end_ms", "at_ms", "confidence", "status");
        assertThat(json.get("speaker_ref").isNull()).isTrue();
        assertThat(json.get("start_ms").isNull()).isTrue();
        assertThat(json.get("end_ms").isNull()).isTrue();
        assertThat(json.get("confidence").isNull()).isTrue();
    }

    private JsonNode tree(Object value) {
        return mapper.readTree(mapper.writeValueAsString(value));
    }
}
