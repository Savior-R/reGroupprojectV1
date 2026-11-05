package com.shijiangtai.m3classroom.api;

import static com.shijiangtai.m3classroom.TestFixtures.CLASS;
import static com.shijiangtai.m3classroom.TestFixtures.SCHOOL_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT2_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT_ID;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER_ID;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shijiangtai.m3classroom.application.RecordingApplicationService;
import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.command.CompletePartCommand;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.PartEtag;
import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.application.view.MultipartUploadView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryRecordingObjectStorage;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class SubmissionControllerIT extends PostgresIntegrationTest {

    private static final long RECORDING_SIZE = 1_024L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskMaterialService materialService;

    @Autowired
    private TaskPublishService publishService;

    @Autowired
    private RecordingApplicationService recordingApplicationService;

    @Autowired
    private SessionRuntimeRegistry runtimeRegistry;

    @Autowired
    private InMemoryRecordingObjectStorage storage;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private TaskAssignmentRepository assignmentRepository;

    @Autowired
    private SessionEventStore eventStore;

    @Autowired
    private JdbcTemplate jdbc;

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
    void submitCreatesSubmissionMarksSessionAndAssignmentAndAppendsEvent() throws Exception {
        String sessionId = createSession();
        startSession(sessionId);
        uploadAllStreams(sessionId);
        endSession(sessionId);

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/submit", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submission_id").isNotEmpty())
                .andExpect(jsonPath("$.task_id").value(taskId.value().toString()))
                .andExpect(jsonPath("$.session_id").value(sessionId))
                .andExpect(jsonPath("$.student_account_id").value(STUDENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("submitted"))
                .andExpect(jsonPath("$.submitted_at").isNotEmpty())
                .andExpect(jsonPath("$.submissionId").doesNotExist());

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"));

        Submission submission = submissionRepository
                .findBySession(sessionId(sessionId)).orElseThrow();
        assertThat(submission.taskId()).isEqualTo(taskId);
        assertThat(submission.studentAccountId()).isEqualTo(STUDENT);
        assertThat(submission.classIdSnapshot()).isEqualTo(CLASS);
        assertThat(assignmentRepository.find(taskId, STUDENT).orElseThrow().status())
                .isEqualTo(AssignmentStatus.SUBMITTED);

        List<SessionEvent> stored = eventStore.after(sessionId(sessionId), 0, 500);
        assertThat(stored)
                .filteredOn(event -> event.type().equals("submission.created"))
                .singleElement()
                .satisfies(event -> assertThat(event.payload()).containsExactly(
                        Map.entry("submission_id", submission.id().toString())));
        Integer eventRows = jdbc.queryForObject("""
                SELECT count(*) FROM m3_classroom.events
                WHERE session_id = ? AND type = 'submission.created'
                """, Integer.class, UUID.fromString(sessionId));
        assertThat(eventRows).isEqualTo(1);
    }

    @Test
    void missingRequiredStreamReturnsRecordingIncomplete() throws Exception {
        String sessionId = createSession();
        startSession(sessionId);
        uploadStream(sessionId, StreamType.CAMERA);
        endSession(sessionId);

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/submit", sessionId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECORDING_INCOMPLETE"))
                .andExpect(jsonPath("$.details.missing_streams.length()").value(2))
                .andExpect(jsonPath("$.details.missing_streams[0]").value("screen"))
                .andExpect(jsonPath("$.details.missing_streams[1]").value("audio"));

        assertThat(submissionRepository.findBySession(sessionId(sessionId))).isEmpty();
    }

    @Test
    void anotherStudentAndTeacherCannotSubmit() throws Exception {
        String sessionId = createSession();
        startSession(sessionId);
        uploadAllStreams(sessionId);
        endSession(sessionId);

        mockMvc.perform(asStudent2(post("/api/v1/sessions/{sessionId}/submit", sessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(asTeacher(post("/api/v1/sessions/{sessionId}/submit", sessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(submissionRepository.findBySession(sessionId(sessionId))).isEmpty();
    }

    @Test
    void submittingTwiceFails() throws Exception {
        String sessionId = createSession();
        startSession(sessionId);
        uploadAllStreams(sessionId);
        endSession(sessionId);
        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/submit", sessionId)))
                .andExpect(status().isOk());

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/submit", sessionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_STATE_INVALID"));

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM m3_classroom.submissions WHERE session_id = ?",
                Integer.class, UUID.fromString(sessionId));
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void submitBeforeEndIsRejected() throws Exception {
        String sessionId = createSession();
        startSession(sessionId);
        uploadAllStreams(sessionId);

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/submit", sessionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_STATE_INVALID"));

        assertThat(submissionRepository.findBySession(sessionId(sessionId))).isEmpty();
    }

    private String createSession() throws Exception {
        MvcResult result = mockMvc.perform(
                        asStudent(post("/api/v1/tasks/{taskId}/sessions", taskId.value())))
                .andExpect(status().isOk())
                .andReturn();
        return read(result, "$.session_id");
    }

    private void startSession(String sessionId) throws Exception {
        SessionActor actor = runtimeRegistry.getOrCreate(sessionId(sessionId));
        List<SessionCommand.RecordingReady.StreamStatus> streams = List.of(
                new SessionCommand.RecordingReady.StreamStatus(StreamType.CAMERA, "ready"),
                new SessionCommand.RecordingReady.StreamStatus(StreamType.SCREEN, "ready"),
                new SessionCommand.RecordingReady.StreamStatus(StreamType.AUDIO, "ready"));
        assertThat(actor.handle(
                new SessionCommand.RecordingReady("ready-" + sessionId, streams)).ok()).isTrue();
        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/start", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("in_progress"));
    }

    private void endSession(String sessionId) throws Exception {
        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/end", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ended"));
    }

    private void uploadAllStreams(String sessionId) {
        uploadStream(sessionId, StreamType.CAMERA);
        uploadStream(sessionId, StreamType.SCREEN);
        uploadStream(sessionId, StreamType.AUDIO);
    }

    private void uploadStream(String sessionId, StreamType type) {
        SessionId id = sessionId(sessionId);
        MultipartUploadView upload = recordingApplicationService.start(student(), id, type);
        storage.recordObjectSize(upload.objectKey(), RECORDING_SIZE);
        String mime = type == StreamType.AUDIO ? "audio/webm" : "video/webm";
        recordingApplicationService.complete(student(), id, type, new CompletePartCommand(
                List.of(new PartEtag(1, "etag-1")), 5_000L, RECORDING_SIZE, mime));
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
