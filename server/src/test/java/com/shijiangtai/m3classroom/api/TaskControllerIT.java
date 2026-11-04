package com.shijiangtai.m3classroom.api;

import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.COURSE_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.OTHER_TEACHER_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.QUESTION1_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.SCHOOL_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.STUDENT_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.TEACHER_ID;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class TaskControllerIT extends PostgresIntegrationTest {
    private static final String FREE_JSON = """
            {
              "mode": "free",
              "topic": "荷塘月色",
              "textbook_version": "部编版",
              "virtual_students": [{
                "name": "林小雨", "avatar": "avatar-01", "personality_text": "性格外向",
                "activity": 0.60, "question_rate": 0.50, "ability": 0.70,
                "attention_baseline": 0.50, "discipline_probability": 0.40,
                "seat_no": 1, "sort_order": 1
              }]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createFreeDraftUsesSnakeCase() throws Exception {
        mockMvc.perform(asTeacher(post("/api/v1/courses/{id}/tasks", COURSE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FREE_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").exists())
                .andExpect(jsonPath("$.mode").value("free"))
                .andExpect(jsonPath("$.rubric_weights.content").value(30))
                .andExpect(jsonPath("$.status").value("draft"));
    }

    @Test
    void publishInterviewReturnsAssignedCount() throws Exception {
        String taskId = createInterviewTask();

        mockMvc.perform(asTeacher(post("/api/v1/tasks/{id}/publish", taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.assigned_count").value(2));
    }

    @Test
    void studentTaskDetailOmitsPrivilegedFields() throws Exception {
        String taskId = createInterviewTask();
        publish(taskId);

        mockMvc.perform(asStudent(get("/api/v1/student/tasks/{id}", taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question_count").value(1))
                .andExpect(jsonPath("$.rubric_weights").doesNotExist())
                .andExpect(jsonPath("$.reference_material").doesNotExist())
                .andExpect(jsonPath("$.published_questions").doesNotExist());
    }

    @Test
    void genericTaskEndpointReturnsStudentViewForStudent() throws Exception {
        String taskId = createInterviewTask();
        publish(taskId);

        mockMvc.perform(asStudent(get("/api/v1/tasks/{id}", taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question_count").value(1))
                .andExpect(jsonPath("$.rubric_weights").doesNotExist());
    }

    @Test
    void otherTeacherGets403() throws Exception {
        mockMvc.perform(post("/api/v1/courses/{id}/tasks", COURSE_ID)
                        .header("X-Account-Id", OTHER_TEACHER_ID.toString())
                        .header("X-School-Id", SCHOOL_ID.toString())
                        .header("X-Account-Type", "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FREE_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEACHER_NOT_IN_GROUP"));
    }

    @Test
    void adminGets403() throws Exception {
        mockMvc.perform(post("/api/v1/courses/{id}/tasks", COURSE_ID)
                        .header("X-Account-Id", UUID.randomUUID().toString())
                        .header("X-School-Id", SCHOOL_ID.toString())
                        .header("X-Account-Type", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FREE_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void missingHeadersGet401() throws Exception {
        mockMvc.perform(post("/api/v1/courses/{id}/tasks", COURSE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FREE_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void patchExplicitNullClearsTextbookVersion() throws Exception {
        String taskId = createFreeTask();

        mockMvc.perform(asTeacher(patch("/api/v1/tasks/{id}", taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"textbook_version\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.textbook_version").value(nullValue()));
    }

    @Test
    void closePublishedTask() throws Exception {
        String taskId = createInterviewTask();
        publish(taskId);

        mockMvc.perform(asTeacher(post("/api/v1/tasks/{id}/close", taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("closed"));
    }

    @Test
    void assignmentsList() throws Exception {
        String taskId = createInterviewTask();
        publish(taskId);

        mockMvc.perform(asTeacher(get("/api/v1/tasks/{id}/assignments", taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void deleteDraft() throws Exception {
        String taskId = createFreeTask();
        mockMvc.perform(asTeacher(delete("/api/v1/tasks/{id}", taskId)))
                .andExpect(status().isNoContent());
    }

    @Test
    void courseTaskListFiltersByStatusAndMode() throws Exception {
        createFreeTask();
        createInterviewTask();

        mockMvc.perform(asTeacher(get("/api/v1/courses/{id}/tasks", COURSE_ID))
                        .param("status", "draft")
                        .param("mode", "free"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    private String createFreeTask() throws Exception {
        MvcResult result = mockMvc.perform(
                        asTeacher(post("/api/v1/courses/{id}/tasks", COURSE_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(FREE_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.task_id");
    }

    private String createInterviewTask() throws Exception {
        String json = """
                {
                  "mode": "interview",
                  "topic": "结构化面试",
                  "question_config": {
                    "question_ids": ["%s"],
                    "total_duration_seconds": 300
                  }
                }
                """.formatted(QUESTION1_ID);
        MvcResult result = mockMvc.perform(
                        asTeacher(post("/api/v1/courses/{id}/tasks", COURSE_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.task_id");
    }

    private void publish(String taskId) throws Exception {
        mockMvc.perform(asTeacher(post("/api/v1/tasks/{id}/publish", taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned_count").value(2));
    }

    private MockHttpServletRequestBuilder asTeacher(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Account-Id", TEACHER_ID.toString())
                .header("X-School-Id", SCHOOL_ID.toString())
                .header("X-Account-Type", "TEACHER");
    }

    private MockHttpServletRequestBuilder asStudent(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Account-Id", STUDENT_ID.toString())
                .header("X-School-Id", SCHOOL_ID.toString())
                .header("X-Account-Type", "STUDENT");
    }
}
