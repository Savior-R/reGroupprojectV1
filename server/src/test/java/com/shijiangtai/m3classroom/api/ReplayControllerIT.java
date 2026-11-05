package com.shijiangtai.m3classroom.api;

import static com.shijiangtai.m3classroom.TestFixtures.CLASS;
import static com.shijiangtai.m3classroom.TestFixtures.SCHOOL_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT2_ID;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER_ID;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryRecordingObjectStorage;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class ReplayControllerIT extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant END = NOW.plusSeconds(120);

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

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionEventStore eventStore;

    @Autowired
    private SessionTextStore textStore;

    @Autowired
    private SubmissionRepository submissionRepository;

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
    void ownerReplayEventsAndTextsUseSnakeCase() throws Exception {
        String sessionId = createSession();
        setStreamOffsets(sessionId);
        uploadAllStreams(sessionId);
        SessionId id = sessionId(sessionId);
        appendEvents(id);
        appendTexts(id);

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/replay", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(sessionId))
                .andExpect(jsonPath("$.mode").value("free"))
                .andExpect(jsonPath("$.duration_ms").value(0))
                .andExpect(jsonPath("$.streams.length()").value(3))
                .andExpect(jsonPath("$.streams[0].stream_type").value("screen"))
                .andExpect(jsonPath("$.streams[0].url").value(containsString("/screen.webm")))
                .andExpect(jsonPath("$.streams[0].offset_ms").value(0))
                .andExpect(jsonPath("$.streams[0].duration_ms").value(5_000))
                .andExpect(jsonPath("$.streams[0].mime").value("video/webm"))
                .andExpect(jsonPath("$.streams[1].stream_type").value("camera"))
                .andExpect(jsonPath("$.streams[1].url").value(containsString("/camera.webm")))
                .andExpect(jsonPath("$.streams[1].offset_ms").value(1_500))
                .andExpect(jsonPath("$.streams[2].stream_type").value("audio"))
                .andExpect(jsonPath("$.streams[2].offset_ms").value(250))
                .andExpect(jsonPath("$.streams[2].mime").value("audio/webm"))
                .andExpect(jsonPath("$.streams[0].streamType").doesNotExist())
                .andExpect(jsonPath("$.sessionId").doesNotExist());

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/events", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].event_id").isNotEmpty())
                .andExpect(jsonPath("$.items[0].seq").value(1))
                .andExpect(jsonPath("$.items[0].type").value("session.started"))
                .andExpect(jsonPath("$.items[0].at_ms").value(0))
                .andExpect(jsonPath("$.items[0].eventId").doesNotExist())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100));

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/events", sessionId))
                        .param("after_seq", "1")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].seq").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.size").value(1));

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/texts", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].speaker_type").value("teacher"))
                .andExpect(jsonPath("$[0].speaker_ref").value(TEACHER_ID.toString()))
                .andExpect(jsonPath("$[0].source").value("asr"))
                .andExpect(jsonPath("$[0].kind").value("utterance"))
                .andExpect(jsonPath("$[0].text").value("同学们好"))
                .andExpect(jsonPath("$[0].start_ms").value(0))
                .andExpect(jsonPath("$[0].end_ms").value(1_000))
                .andExpect(jsonPath("$[0].at_ms").value(500))
                .andExpect(jsonPath("$[0].confidence").value(0.95))
                .andExpect(jsonPath("$[0].status").value("final"))
                .andExpect(jsonPath("$[0].speakerType").doesNotExist());

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/texts", sessionId))
                        .param("speaker_type", "teacher"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].speaker_type").value("teacher"));

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/texts", sessionId))
                        .param("speaker_type", "candidate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/texts", sessionId))
                        .param("speaker_type", "principal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/events", sessionId))
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/events", sessionId))
                        .param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void incompleteRecordingReturnsMissingStreams() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/replay", sessionId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECORDING_INCOMPLETE"))
                .andExpect(jsonPath("$.details.missing_streams.length()").value(3))
                .andExpect(jsonPath("$.details.missing_streams[0]").value("camera"))
                .andExpect(jsonPath("$.details.missing_streams[1]").value("screen"))
                .andExpect(jsonPath("$.details.missing_streams[2]").value("audio"));

        uploadStream(sessionId, "camera");

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/replay", sessionId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECORDING_INCOMPLETE"))
                .andExpect(jsonPath("$.details.missing_streams.length()").value(2))
                .andExpect(jsonPath("$.details.missing_streams[0]").value("screen"))
                .andExpect(jsonPath("$.details.missing_streams[1]").value("audio"));
    }

    @Test
    void temporaryReplayIsForbiddenButSubmittedReplayIsVisibleToTeacher() throws Exception {
        String sessionId = createSession();
        uploadAllStreams(sessionId);
        SessionId id = sessionId(sessionId);
        appendEvents(id);
        appendTexts(id);

        mockMvc.perform(asTeacher(get("/api/v1/sessions/{sessionId}/replay", sessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(asTeacher(get("/api/v1/sessions/{sessionId}/events", sessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(asTeacher(get("/api/v1/sessions/{sessionId}/texts", sessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        submitSession(sessionId);

        mockMvc.perform(asTeacher(get("/api/v1/sessions/{sessionId}/replay", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(sessionId))
                .andExpect(jsonPath("$.duration_ms").value(120_000))
                .andExpect(jsonPath("$.streams.length()").value(3));
        mockMvc.perform(asTeacher(get("/api/v1/sessions/{sessionId}/events", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
        mockMvc.perform(asTeacher(get("/api/v1/sessions/{sessionId}/texts", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(withIdentity(
                        get("/api/v1/sessions/{sessionId}/replay", sessionId),
                        UUID.randomUUID(), SCHOOL_ID, "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(withIdentity(
                        get("/api/v1/sessions/{sessionId}/replay", sessionId),
                        STUDENT_ID, UUID.randomUUID(), "STUDENT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SCHOOL_ACCESS"));
        mockMvc.perform(withIdentity(
                        get("/api/v1/sessions/{sessionId}/replay", sessionId),
                        UUID.randomUUID(), SCHOOL_ID, "TEACHER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(asStudent2(get("/api/v1/sessions/{sessionId}/replay", sessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void submittedSessionWithoutSubmissionRowIsHiddenFromTeacher() throws Exception {
        String sessionId = createSession();
        uploadAllStreams(sessionId);

        Session session = sessionRepository.findById(sessionId(sessionId)).orElseThrow();
        session.start(NOW);
        session.end(END);
        session.submit(END);
        sessionRepository.save(session);

        mockMvc.perform(asTeacher(get("/api/v1/sessions/{sessionId}/replay", sessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(asTeacher(get("/api/v1/sessions/{sessionId}/events", sessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/replay", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(sessionId));
    }

    @Test
    void unknownSessionAndMissingIdentityAreRejected() throws Exception {
        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}/replay",
                        UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/replay", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String createSession() throws Exception {
        MvcResult result = mockMvc.perform(
                        asStudent(post("/api/v1/tasks/{taskId}/sessions", taskId.value())))
                .andExpect(status().isOk())
                .andReturn();
        return read(result, "$.session_id");
    }

    private void setStreamOffsets(String sessionId) {
        Session session = sessionRepository.findById(sessionId(sessionId)).orElseThrow();
        session.putStreamOffset(StreamType.CAMERA, 1_500);
        session.putStreamOffset(StreamType.AUDIO, 250);
        sessionRepository.save(session);
    }

    private void uploadAllStreams(String sessionId) throws Exception {
        uploadStream(sessionId, "camera");
        uploadStream(sessionId, "screen");
        uploadStream(sessionId, "audio");
    }

    private void uploadStream(String sessionId, String streamType) throws Exception {
        MvcResult started = mockMvc.perform(asStudent(post(
                        "/api/v1/sessions/{sessionId}/recordings/{streamType}/multipart/start",
                        sessionId, streamType)))
                .andExpect(status().isOk())
                .andReturn();
        String objectKey = read(started, "$.object_key");
        storage.recordObjectSize(objectKey, 1_024);
        String mime = "audio".equals(streamType) ? "audio/webm" : "video/webm";
        mockMvc.perform(asStudent(post(
                        "/api/v1/sessions/{sessionId}/recordings/{streamType}/multipart/complete",
                        sessionId, streamType))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parts":[{"part_number":1,"etag":"etag-1"}],
                                 "duration_ms":5000,"size":1024,"mime":"%s"}
                                """.formatted(mime)))
                .andExpect(status().isOk());
    }

    private void submitSession(String sessionId) {
        Session session = sessionRepository.findById(sessionId(sessionId)).orElseThrow();
        session.start(NOW);
        session.end(END);
        session.submit(END);
        sessionRepository.save(session);
        submissionRepository.save(Submission.create(
                UUID.randomUUID(), taskId, STUDENT, session.id(), CLASS, END));
    }

    private void appendEvents(SessionId sessionId) {
        eventStore.append(new SessionEvent(
                UUID.randomUUID(), sessionId, 1, "session.started", 0,
                Map.of("mode", "free"), NOW));
        eventStore.append(new SessionEvent(
                UUID.randomUUID(), sessionId, 2, "teacher.speech", 1_000,
                Map.of("text_id", UUID.randomUUID().toString(), "count", 2), NOW));
        eventStore.append(new SessionEvent(
                UUID.randomUUID(), sessionId, 3, "session.ended", 5_000,
                Map.of("duration_ms", 5_000), NOW));
    }

    private void appendTexts(SessionId sessionId) {
        textStore.saveAll(List.of(
                new SessionText(
                        UUID.randomUUID(), sessionId, "teacher", TEACHER_ID,
                        "asr", "utterance", "同学们好", 0L, 1_000L, 500L,
                        new BigDecimal("0.95"), null, null, "final"),
                new SessionText(
                        UUID.randomUUID(), sessionId, "student", STUDENT_ID,
                        "asr", "utterance", "老师好", 1_000L, 2_000L, 1_500L,
                        new BigDecimal("0.80"), null, null, "final")));
    }

    private static SessionId sessionId(String value) {
        return new SessionId(UUID.fromString(value));
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

    private MockHttpServletRequestBuilder asTeacher(MockHttpServletRequestBuilder builder) {
        return withIdentity(builder, TEACHER_ID, SCHOOL_ID, "TEACHER");
    }

    private MockHttpServletRequestBuilder withIdentity(
            MockHttpServletRequestBuilder builder, UUID accountId, UUID schoolId, String type) {
        return builder.header("X-Account-Id", accountId.toString())
                .header("X-School-Id", schoolId.toString())
                .header("X-Account-Type", type);
    }
}
