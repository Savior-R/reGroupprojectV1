package com.shijiangtai.m3classroom.api;

import static com.shijiangtai.m3classroom.TestFixtures.SCHOOL_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT2_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT_ID;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.application.SessionApplicationService;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionFrameStore;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.FrameStatus;
import com.shijiangtai.m3classroom.domain.session.FrameType;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionFrame;
import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryMaterialObjectStorage;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.m3classroom.support.FakeOcrClient;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@AutoConfigureMockMvc
class PerceptionScreenIT extends PostgresIntegrationTest {

    private static final String SCREEN_PATH = "/api/v1/sessions/{sessionId}/perception/screen";
    private static final byte[] JPEG_BYTES =
            new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x01, 0x02, 0x03};
    private static final long POLL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long POLL_INTERVAL_MILLIS = 50L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskMaterialService materialService;

    @Autowired
    private TaskPublishService publishService;

    @Autowired
    private SessionApplicationService sessionApplicationService;

    @Autowired
    private SessionRuntimeRegistry runtimeRegistry;

    @Autowired
    private SessionFrameStore frameStore;

    @Autowired
    private SessionEventStore eventStore;

    @Autowired
    private InMemoryMaterialObjectStorage objectStorage;

    @Autowired
    private FakeOcrClient ocrClient;

    private String sessionId;

    @TestConfiguration
    static class FakePerceptionAiConfig {
        @Bean
        @Primary
        FakeOcrClient fakeOcrClient() {
            return new FakeOcrClient();
        }
    }

    @BeforeEach
    void startSession() {
        ocrClient.reset();
        TaskId taskId = publishFreeTask();
        sessionId = sessionApplicationService.createSession(student(), taskId)
                .sessionId().toString();
        start(new SessionId(UUID.fromString(sessionId)));
    }

    @AfterEach
    void stopActor() {
        if (sessionId != null) {
            runtimeRegistry.remove(new SessionId(UUID.fromString(sessionId)));
        }
    }

    @Test
    void uploadReturns202ThenProcessesFrameAndEmitsEvent() throws Exception {
        MvcResult result = mockMvc.perform(asStudent(multipart(SCREEN_PATH, sessionId)
                        .file(jpegFile())
                        .param("at_ms", "1000")
                        .param("change_score", "0.3")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.frame_id").isString())
                .andExpect(jsonPath("$.status").value("pending"))
                .andReturn();

        String frameIdValue = JsonPath.read(
                result.getResponse().getContentAsString(), "$.frame_id");
        UUID frameId = UUID.fromString(frameIdValue);
        String expectedKey = "m3/frames/" + sessionId + "/" + frameId + ".jpg";

        SessionFrame processed = awaitFrame(frameId, FrameStatus.PROCESSED);
        assertThat(processed.frameType()).isEqualTo(FrameType.SLIDE_CHANGE);
        assertThat(processed.objectKey()).isEqualTo(expectedKey);
        assertThat(processed.text()).isEqualTo(FakeOcrClient.DEFAULT_RESULT.text());
        assertThat(objectStorage.get(expectedKey)).isEqualTo(JPEG_BYTES);

        SessionEvent event = awaitEvent(sid(), "perception.screen_changed");
        assertThat(String.valueOf(event.payload().get("frame_id"))).isEqualTo(frameIdValue);
        assertThat(String.valueOf(event.payload().get("frame_type"))).isEqualTo("slide_change");
        assertThat(((Number) event.payload().get("at_ms")).longValue()).isEqualTo(1000L);
    }

    @Test
    void ocrFailureMarksFrameFailedWithoutEventAndSessionStaysInProgress() throws Exception {
        ocrClient.failWith(new RuntimeException("OCR 服务不可用"));

        MvcResult result = mockMvc.perform(asStudent(multipart(SCREEN_PATH, sessionId)
                        .file(jpegFile())
                        .param("at_ms", "1000")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending"))
                .andReturn();
        String frameIdValue = JsonPath.read(
                result.getResponse().getContentAsString(), "$.frame_id");

        SessionFrame failed = awaitFrame(UUID.fromString(frameIdValue), FrameStatus.FAILED);
        assertThat(failed.errorMessage()).contains("OCR 服务不可用");
        assertThat(eventStore.after(sid(), 0, 100))
                .noneMatch(event -> event.type().equals("perception.screen_changed"));
        assertThat(sessionApplicationService.get(student(), sid()).status())
                .isEqualTo("in_progress");
    }

    @Test
    void nonOwnerStudentIsForbidden() throws Exception {
        mockMvc.perform(asStudent2(multipart(SCREEN_PATH, sessionId)
                        .file(jpegFile())
                        .param("at_ms", "1000")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void preparingSessionReturnsConflict() throws Exception {
        TaskId otherTask = publishFreeTask();
        String preparingSessionId = sessionApplicationService
                .createSession(student(), otherTask).sessionId().toString();

        mockMvc.perform(asStudent(multipart(SCREEN_PATH, preparingSessionId)
                        .file(jpegFile())
                        .param("at_ms", "1000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_STATE_INVALID"));
    }

    @Test
    void invalidParamsReturn400() throws Exception {
        mockMvc.perform(asStudent(multipart(SCREEN_PATH, sessionId)
                        .file(new MockMultipartFile(
                                "file", "screen.png", "image/png", JPEG_BYTES))
                        .param("at_ms", "1000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(asStudent(multipart(SCREEN_PATH, sessionId)
                        .file(new MockMultipartFile(
                                "file", "big.jpg", "image/jpeg",
                                new byte[5 * 1024 * 1024 + 1]))
                        .param("at_ms", "1000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(asStudent(multipart(SCREEN_PATH, sessionId)
                        .file(jpegFile())
                        .param("at_ms", "-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private TaskId publishFreeTask() {
        TaskView view = taskApplicationService.createDraft(
                teacher(), freeCommandWithStudent());
        TaskId taskId = new TaskId(view.taskId());
        materialService.upload(
                teacher(), taskId, "a.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于测试发布。"
                        .getBytes(StandardCharsets.UTF_8));
        publishService.publish(teacher(), taskId);
        return taskId;
    }

    private void start(SessionId id) {
        SessionActor actor = runtimeRegistry.getOrCreate(id);
        List<SessionCommand.RecordingReady.StreamStatus> streams = List.of(
                new SessionCommand.RecordingReady.StreamStatus(StreamType.CAMERA, "ready"),
                new SessionCommand.RecordingReady.StreamStatus(StreamType.SCREEN, "ready"),
                new SessionCommand.RecordingReady.StreamStatus(StreamType.AUDIO, "ready"));
        assertThat(actor.handle(
                new SessionCommand.RecordingReady("ready-" + UUID.randomUUID(), streams)).ok())
                .isTrue();
        sessionApplicationService.start(student(), id);
    }

    private SessionId sid() {
        return new SessionId(UUID.fromString(sessionId));
    }

    private SessionFrame awaitFrame(UUID frameId, FrameStatus status)
            throws InterruptedException {
        long deadline = System.nanoTime() + POLL_TIMEOUT_NANOS;
        SessionFrame last = null;
        while (System.nanoTime() < deadline) {
            last = frameStore.find(frameId).orElse(null);
            if (last != null && last.status() == status) {
                return last;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError(
                "frame " + frameId + " did not reach " + status + "; last=" + last);
    }

    private SessionEvent awaitEvent(SessionId id, String type) throws InterruptedException {
        long deadline = System.nanoTime() + POLL_TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            Optional<SessionEvent> event = eventStore.after(id, 0, 100).stream()
                    .filter(candidate -> candidate.type().equals(type))
                    .findFirst();
            if (event.isPresent()) {
                return event.get();
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError(
                "event " + type + " not found for session " + id.value());
    }

    private static MockMultipartFile jpegFile() {
        return new MockMultipartFile("file", "screen.jpg", "image/jpeg", JPEG_BYTES);
    }

    private static MockMultipartHttpServletRequestBuilder asStudent(
            MockMultipartHttpServletRequestBuilder builder) {
        return withIdentity(builder, STUDENT_ID, SCHOOL_ID, "STUDENT");
    }

    private static MockMultipartHttpServletRequestBuilder asStudent2(
            MockMultipartHttpServletRequestBuilder builder) {
        return withIdentity(builder, STUDENT2_ID, SCHOOL_ID, "STUDENT");
    }

    private static MockMultipartHttpServletRequestBuilder withIdentity(
            MockMultipartHttpServletRequestBuilder builder,
            UUID accountId,
            UUID schoolId,
            String type) {
        return builder.header("X-Account-Id", accountId.toString())
                .header("X-School-Id", schoolId.toString())
                .header("X-Account-Type", type);
    }
}
