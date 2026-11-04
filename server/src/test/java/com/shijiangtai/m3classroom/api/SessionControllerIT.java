package com.shijiangtai.m3classroom.api;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.COURSE_ID;
import static com.shijiangtai.m3classroom.TestFixtures.SCHOOL_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT_ID;
import static com.shijiangtai.m3classroom.TestFixtures.STUDENT2_ID;
import static com.shijiangtai.m3classroom.TestFixtures.TEACHER_ID;
import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.QUESTION1_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shijiangtai.m3classroom.application.SessionActor;
import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class SessionControllerIT extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskMaterialService materialService;

    @Autowired
    private TaskPublishService publishService;

    @Autowired
    private SessionRuntimeRegistry runtimeRegistry;

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
    void createSessionReturnsSnakeCaseView() throws Exception {
        mockMvc.perform(asStudent(post("/api/v1/tasks/{taskId}/sessions", taskId.value())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").exists())
                .andExpect(jsonPath("$.task_id").value(taskId.value().toString()))
                .andExpect(jsonPath("$.course_id").value(COURSE_ID.toString()))
                .andExpect(jsonPath("$.session_type").value("task"))
                .andExpect(jsonPath("$.mode").value("free"))
                .andExpect(jsonPath("$.status").value("preparing"))
                .andExpect(jsonPath("$.attempt_no").value(1))
                .andExpect(jsonPath("$.started_at").value(nullValue()))
                .andExpect(jsonPath("$.ended_at").value(nullValue()))
                .andExpect(jsonPath("$.duration_ms").value(nullValue()))
                .andExpect(jsonPath("$.stream_offsets").isMap())
                .andExpect(jsonPath("$.last_seq").value(0))
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.rubric_weights").doesNotExist())
                .andExpect(jsonPath("$.reference_material").doesNotExist())
                .andExpect(jsonPath("$.answer_points").doesNotExist());
    }

    @Test
    void getSessionReturnsOwnView() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(sessionId))
                .andExpect(jsonPath("$.status").value("preparing"));
    }

    @Test
    void interviewRestEndTransitionsToEnded() throws Exception {
        TaskId interviewTaskId = publishInterviewTask();
        String sessionId = createSession(interviewTaskId);
        SessionActor actor = runtimeRegistry.getOrCreate(
                new SessionId(UUID.fromString(sessionId)));
        List<SessionCommand.RecordingReady.StreamStatus> streams = List.of(
                new SessionCommand.RecordingReady.StreamStatus(StreamType.CAMERA, "ready"),
                new SessionCommand.RecordingReady.StreamStatus(StreamType.AUDIO, "ready"));
        assertThat(actor.handle(
                new SessionCommand.RecordingReady("ready-interview-it", streams)).ok()).isTrue();

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/start", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("interview"))
                .andExpect(jsonPath("$.status").value("in_progress"));

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/end", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ended"));
    }

    @Test
    void startBeforeRecordingReadyReturns409() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/start", sessionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_STATE_INVALID"));
    }

    @Test
    void cancelReturns204AndCancelsSession() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(asStudent(post("/api/v1/sessions/{sessionId}/cancel", sessionId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(asStudent(get("/api/v1/sessions/{sessionId}", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void otherStudentIsForbidden() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(asStudent2(get("/api/v1/sessions/{sessionId}", sessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void teacherCannotReadUnsubmittedSession() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(asTeacher(get("/api/v1/sessions/{sessionId}", sessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminIsForbidden() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(withIdentity(get("/api/v1/sessions/{sessionId}", sessionId),
                        UUID.randomUUID(), SCHOOL_ID, "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void missingHeadersReturn401() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(get("/api/v1/sessions/{sessionId}", sessionId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void unassignedStudentCannotCreateSession() throws Exception {
        mockMvc.perform(withIdentity(
                        post("/api/v1/tasks/{taskId}/sessions", taskId.value()),
                        UUID.randomUUID(), SCHOOL_ID, "STUDENT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private String createSession() throws Exception {
        return createSession(taskId);
    }

    private String createSession(TaskId targetTaskId) throws Exception {
        MvcResult result = mockMvc.perform(
                        asStudent(post("/api/v1/tasks/{taskId}/sessions", targetTaskId.value())))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.session_id");
    }

    private TaskId publishInterviewTask() {
        CreateTaskCommand command = new CreateTaskCommand(
                COURSE, TaskMode.INTERVIEW, "结构化面试", null,
                null, null, null, null, List.of(),
                new QuestionConfig(List.of(new QuestionId(QUESTION1_ID)), 300));
        TaskView view = taskApplicationService.createDraft(teacher(), command);
        TaskId id = new TaskId(view.taskId());
        publishService.publish(teacher(), id);
        return id;
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
