package com.shijiangtai.m3classroom.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.api.RecordingRequestMapper;
import com.shijiangtai.m3classroom.api.error.ApiErrorResponse;
import com.shijiangtai.m3classroom.api.error.GlobalExceptionHandler;
import com.shijiangtai.m3classroom.application.view.MultipartUploadView;
import com.shijiangtai.m3classroom.application.view.PartUrlView;
import com.shijiangtai.m3classroom.application.view.RecordingStreamView;
import com.shijiangtai.m3classroom.application.view.SubmissionView;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire contract for the recording multipart API and submission view (02 §4.3, 05B §4/§5).
 *
 * <p>Fixtures are read as JSON trees, so assertions are independent of whitespace and property
 * order. The mapper mirrors the global {@code spring.jackson.property-naming-strategy=SNAKE_CASE}
 * configuration; any DTO rename or camelCase regression fails these tests.
 */
class RecordingContractTest {

    private static final UUID SUBMISSION_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000007a1");
    private static final UUID TASK_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000007a2");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000007a3");
    private static final UUID STUDENT_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000007a4");

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void multipartStartResponseFreezesUploadCoordinates() {
        MultipartUploadView view = new MultipartUploadView(
                "upload-1",
                "recordings/00000000-0000-0000-0000-000000000001/task/"
                        + "00000000-0000-0000-0000-0000000007a2/"
                        + "00000000-0000-0000-0000-0000000007a3/camera.webm",
                5_242_880L);

        JsonNode json = tree(view);

        assertThat(json.propertyNames())
                .containsExactlyInAnyOrder("upload_id", "object_key", "part_size");
        assertThat(json.get("upload_id").asString()).isEqualTo("upload-1");
        assertThat(json.get("object_key").asString())
                .isEqualTo(view.objectKey());
        assertThat(json.get("part_size").asLong()).isEqualTo(5_242_880L);
        assertThat(json.has("uploadId")).isFalse();
        assertThat(json.has("objectKey")).isFalse();
        assertThat(json.has("partSize")).isFalse();
    }

    @Test
    void partUrlResponseFreezesPresignedUrlShape() {
        PartUrlView view = new PartUrlView(
                "http://localhost:9000/shijiangtai/recordings/key/camera.webm"
                        + "?uploadId=upload-1&partNumber=2&X-Amz-Expires=900",
                2,
                900L);

        JsonNode json = tree(view);

        assertThat(json.propertyNames())
                .containsExactlyInAnyOrder("url", "part_number", "expires_in_seconds");
        assertThat(json.get("url").asString()).isEqualTo(view.url());
        assertThat(json.get("part_number").asInt()).isEqualTo(2);
        assertThat(json.get("expires_in_seconds").asLong()).isEqualTo(900L);
        assertThat(json.has("partNumber")).isFalse();
        assertThat(json.has("expiresInSeconds")).isFalse();
    }

    @Test
    void completeRequestAndPartUrlRequestReadSnakeCaseBodies() {
        RecordingRequestMapper.CompleteRequest complete = mapper.readValue("""
                {"parts":[{"part_number":1,"etag":"etag-1"}],
                 "duration_ms":5000,"size":1024,"mime":"video/webm"}
                """, RecordingRequestMapper.CompleteRequest.class);

        assertThat(complete.parts()).hasSize(1);
        assertThat(complete.parts().get(0).partNumber()).isEqualTo(1);
        assertThat(complete.parts().get(0).etag()).isEqualTo("etag-1");
        assertThat(complete.durationMs()).isEqualTo(5_000L);
        assertThat(complete.size()).isEqualTo(1_024L);
        assertThat(complete.mime()).isEqualTo("video/webm");

        JsonNode encoded = tree(complete);
        assertThat(encoded.propertyNames())
                .containsExactlyInAnyOrder("parts", "duration_ms", "size", "mime");
        assertThat(encoded.get("parts").get(0).propertyNames())
                .containsExactlyInAnyOrder("part_number", "etag");
        assertThat(encoded.get("parts").get(0).get("part_number").asInt()).isEqualTo(1);
        assertThat(encoded.get("parts").get(0).get("etag").asString()).isEqualTo("etag-1");
        assertThat(encoded.get("duration_ms").asLong()).isEqualTo(5_000L);
        assertThat(encoded.get("size").asLong()).isEqualTo(1_024L);
        assertThat(encoded.get("mime").asString()).isEqualTo("video/webm");

        RecordingRequestMapper.PartUrlRequest partUrl = mapper.readValue(
                "{\"part_number\":3}", RecordingRequestMapper.PartUrlRequest.class);
        assertThat(partUrl.partNumber()).isEqualTo(3);
        assertThat(tree(partUrl).propertyNames()).containsExactly("part_number");
    }

    @Test
    void recordingStreamViewFreezesUploadMetadata() {
        RecordingStreamView view = new RecordingStreamView(
                "camera",
                "uploaded",
                "upload-1",
                "recordings/00000000-0000-0000-0000-000000000001/task/"
                        + "00000000-0000-0000-0000-0000000007a2/"
                        + "00000000-0000-0000-0000-0000000007a3/camera.webm",
                3,
                15_728_640L,
                120_000L,
                1_500L,
                "video/webm",
                5_242_880L);

        JsonNode json = tree(view);

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "stream_type", "status", "upload_id", "object_key", "part_size",
                "parts_uploaded", "bytes_uploaded", "duration_ms", "offset_ms", "mime");
        assertThat(json.get("stream_type").asString()).isEqualTo("camera");
        assertThat(json.get("status").asString()).isEqualTo("uploaded");
        assertThat(json.get("upload_id").asString()).isEqualTo("upload-1");
        assertThat(json.get("object_key").asString()).isEqualTo(view.objectKey());
        assertThat(json.get("part_size").asLong()).isEqualTo(5_242_880L);
        assertThat(json.get("parts_uploaded").asInt()).isEqualTo(3);
        assertThat(json.get("bytes_uploaded").asLong()).isEqualTo(15_728_640L);
        assertThat(json.get("duration_ms").asLong()).isEqualTo(120_000L);
        assertThat(json.get("offset_ms").asLong()).isEqualTo(1_500L);
        assertThat(json.get("mime").asString()).isEqualTo("video/webm");
    }

    @Test
    void submissionViewFreezesSubmissionReceipt() {
        SubmissionView view = new SubmissionView(
                SUBMISSION_ID, TASK_ID, SESSION_ID, STUDENT_ID, "submitted",
                "2026-11-05T10:15:30Z");

        JsonNode json = tree(view);

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "submission_id", "task_id", "session_id", "student_account_id",
                "status", "submitted_at");
        assertThat(json.get("submission_id").asString()).isEqualTo(SUBMISSION_ID.toString());
        assertThat(json.get("task_id").asString()).isEqualTo(TASK_ID.toString());
        assertThat(json.get("session_id").asString()).isEqualTo(SESSION_ID.toString());
        assertThat(json.get("student_account_id").asString()).isEqualTo(STUDENT_ID.toString());
        assertThat(json.get("status").asString()).isEqualTo("submitted");
        assertThat(json.get("submitted_at").asString()).isEqualTo("2026-11-05T10:15:30Z");
        assertThat(json.has("submissionId")).isFalse();
        assertThat(json.has("studentAccountId")).isFalse();
    }

    @Test
    void recordingIncompleteErrorBodyFreezesMissingStreamsDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-recording-incomplete");

        ResponseEntity<ApiErrorResponse> response = new GlobalExceptionHandler().handleDomain(
                new DomainException(ErrorCode.RECORDING_INCOMPLETE, "录制不完整",
                        Map.of("missing_streams", List.of("camera", "screen", "audio"))),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).isNotNull();

        JsonNode json = tree(response.getBody());
        assertThat(json.propertyNames())
                .containsExactlyInAnyOrder("code", "message", "details", "trace_id");
        assertThat(json.get("code").asString()).isEqualTo("RECORDING_INCOMPLETE");
        assertThat(json.get("message").asString()).isEqualTo("录制不完整");
        assertThat(json.get("trace_id").asString()).isEqualTo("trace-recording-incomplete");
        assertThat(json.get("details").propertyNames()).containsExactly("missing_streams");

        List<String> missingStreams = new ArrayList<>();
        json.get("details").get("missing_streams").forEach(node -> missingStreams.add(node.asString()));
        assertThat(missingStreams).containsExactly("camera", "screen", "audio");
        assertThat(json.has("traceId")).isFalse();
    }

    private JsonNode tree(Object value) {
        return mapper.readTree(mapper.writeValueAsString(value));
    }
}
