package com.shijiangtai.m3classroom.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.application.view.MultipartUploadView;
import com.shijiangtai.m3classroom.application.view.RecordingStreamView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Uses the real Spring-configured {@link ObjectMapper} rather than a locally configured
 * {@code SNAKE_CASE} mapper, so a regression in {@code spring.jackson.property-naming-strategy}
 * fails this contract test, not only the MockMvc integration tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecordingWireContractTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void springObjectMapperSerializesRecordingViewsWithSnakeCaseKeys() {
        JsonNode start = tree(new MultipartUploadView(
                "upload-1", "recordings/school/task/session/camera.webm", 5_242_880L));
        assertThat(start.propertyNames())
                .containsExactlyInAnyOrder("upload_id", "object_key", "part_size");

        JsonNode stream = tree(new RecordingStreamView(
                "camera", "uploaded", "upload-1",
                "recordings/school/task/session/camera.webm",
                1, 1_024L, 5_000L, 0L, "video/webm", 5_242_880L));
        assertThat(stream.propertyNames()).containsExactlyInAnyOrder(
                "stream_type", "status", "upload_id", "object_key", "part_size",
                "parts_uploaded", "bytes_uploaded", "duration_ms", "offset_ms", "mime");
        assertThat(stream.has("streamType")).isFalse();
        assertThat(stream.has("uploadId")).isFalse();
    }

    private JsonNode tree(Object value) {
        return objectMapper.readTree(objectMapper.writeValueAsString(value));
    }
}
