package com.shijiangtai.m3classroom.api;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.SCHOOL_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT2_ID;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER_ID;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryRecordingObjectStorage;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class RecordingControllerIT extends PostgresIntegrationTest {

    private static final long PART_SIZE = 5L * 1024 * 1024;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskMaterialService materialService;

    @Autowired
    private TaskPublishService publishService;

    @Autowired
    private InMemoryRecordingObjectStorage storage;

    private TaskId taskId;

    @BeforeEach
    void publishTask() {
        TaskView view = taskApplicationService.createDraft(teacher(), freeCommandWithStudent());
        taskId = new TaskId(view.taskId());
        materialService.upload(
                teacher(), taskId, "a.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于测试发布。"
                        .getBytes(StandardCharsets.UTF_8));
        publishService.publish(teacher(), taskId);
    }

    @Test
    void multipartUploadFlowReturnsSnakeCaseViewsAndListsStreams() throws Exception {
        String sessionId = createSession();

        MvcResult camera = start(sessionId, "camera");
        String cameraKey = read(camera, "$.object_key");
        String cameraUploadId = read(camera, "$.upload_id");
        MvcResult screen = start(sessionId, "screen");
        MvcResult audio = start(sessionId, "audio");

        String screenKey = read(screen, "$.object_key");
        String audioKey = read(audio, "$.object_key");
        assertThat(cameraKey).isEqualTo("recordings/" + SCHOOL_ID + "/task/" + taskId.value()
                + "/" + sessionId + "/camera.webm");
        assertThat(cameraUploadId).isNotBlank();
        assertThat(screenKey).endsWith("/screen.webm");
        assertThat(audioKey).endsWith("/audio.webm");

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/recordings/{streamType}"
                        + "/multipart/part-url", sessionId, "camera"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"part_number\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString(cameraKey)))
                .andExpect(jsonPath("$.url").value(containsString("partNumber=1")))
                .andExpect(jsonPath("$.part_number").value(1))
                .andExpect(jsonPath("$.expires_in_seconds").value(900))
                .andExpect(jsonPath("$.partNumber").doesNotExist());

        storage.recordObjectSize(cameraKey, 2048);
        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/recordings/{streamType}"
                        + "/multipart/complete", sessionId, "camera"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parts":[{"part_number":1,"etag":"etag-1"}],
                                 "duration_ms":5000,"size":2048,"mime":"video/webm"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stream_type").value("camera"))
                .andExpect(jsonPath("$.status").value("uploaded"))
                .andExpect(jsonPath("$.upload_id").value(cameraUploadId))
                .andExpect(jsonPath("$.object_key").value(cameraKey))
                .andExpect(jsonPath("$.parts_uploaded").value(1))
                .andExpect(jsonPath("$.bytes_uploaded").value(2048))
                .andExpect(jsonPath("$.duration_ms").value(5000))
                .andExpect(jsonPath("$.offset_ms").value(0))
                .andExpect(jsonPath("$.mime").value("video/webm"))
                .andExpect(jsonPath("$.part_size").value(PART_SIZE))
                .andExpect(jsonPath("$.streamType").doesNotExist());

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/recordings", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].stream_type").value("camera"))
                .andExpect(jsonPath("$[0].status").value("uploaded"))
                .andExpect(jsonPath("$[1].stream_type").value("screen"))
                .andExpect(jsonPath("$[1].status").value("uploading"))
                .andExpect(jsonPath("$[2].stream_type").value("audio"))
                .andExpect(jsonPath("$[2].status").value("uploading"));
    }

    @Test
    void abortReturns204AndMarksStreamFailed() throws Exception {
        String sessionId = createSession();
        start(sessionId, "audio");

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/recordings/{streamType}"
                        + "/multipart/abort", sessionId, "audio")))
                .andExpect(status().isNoContent());
        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/recordings/{streamType}"
                        + "/multipart/abort", sessionId, "audio")))
                .andExpect(status().isNoContent());

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/recordings", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stream_type").value("audio"))
                .andExpect(jsonPath("$[0].status").value("failed"));
    }

    @Test
    void completeSizeMismatchMarksStreamFailedAndPersists() throws Exception {
        String sessionId = createSession();
        MvcResult camera = start(sessionId, "camera");
        storage.recordObjectSize(read(camera, "$.object_key"), 10);

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/recordings/{streamType}"
                        + "/multipart/complete", sessionId, "camera"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parts":[{"part_number":1,"etag":"etag-1"}],
                                 "duration_ms":5000,"size":20,"mime":"video/webm"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/recordings", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stream_type").value("camera"))
                .andExpect(jsonPath("$[0].status").value("failed"));
    }

    @Test
    void completeRejectsNullPart() throws Exception {
        String sessionId = createSession();
        start(sessionId, "camera");

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/recordings/{streamType}"
                        + "/multipart/complete", sessionId, "camera"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parts":[null],"duration_ms":1000,"size":10,"mime":"video/webm"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void otherStudentAndAdminAreForbidden() throws Exception {
        String sessionId = createSession();
        start(sessionId, "camera");

        mockMvc.perform(asStudent2(get("/api/v1/sessions/{sessionId}/recordings", sessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(withIdentity(
                        get("/api/v1/sessions/{sessionId}/recordings", sessionId),
                        UUID.randomUUID(), SCHOOL_ID, "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(asStudent2(post("/api/v1/sessions/{sessionId}/recordings/{streamType}"
                        + "/multipart/start", sessionId, "camera")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private String createSession() throws Exception {
        MvcResult result = mockMvc.perform(
                        asStudent(post("/api/v1/tasks/{taskId}/sessions", taskId.value())))
                .andExpect(status().isOk())
                .andReturn();
        return read(result, "$.session_id");
    }

    private MvcResult start(String sessionId, String streamType) throws Exception {
        return mockMvc.perform(asStudent(post(
                        "/api/v1/sessions/{sessionId}/recordings/{streamType}/multipart/start",
                        sessionId, streamType)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upload_id").isNotEmpty())
                .andExpect(jsonPath("$.object_key").isNotEmpty())
                .andExpect(jsonPath("$.part_size").value(PART_SIZE))
                .andExpect(jsonPath("$.uploadId").doesNotExist())
                .andReturn();
    }

    private static <T> T read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private MockHttpServletRequestBuilder asStudent(MockHttpServletRequestBuilder builder) {
        return withIdentity(builder, STUDENT_ID, SCHOOL_ID, "STUDENT");
    }

    private MockHttpServletRequestBuilder asStudent2(MockHttpServletRequestBuilder builder) {
        return withIdentity(builder, STUDENT2_ID, SCHOOL_ID, "STUDENT");
    }

    private MockHttpServletRequestBuilder withIdentity(
            MockHttpServletRequestBuilder builder, UUID accountId, UUID schoolId, String type) {
        return builder.header("X-Account-Id", accountId.toString())
                .header("X-School-Id", schoolId.toString())
                .header("X-Account-Type", type);
    }
}
