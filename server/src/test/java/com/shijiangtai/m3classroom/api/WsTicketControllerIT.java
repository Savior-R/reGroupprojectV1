package com.shijiangtai.m3classroom.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Duration;
import java.time.Instant;
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
class WsTicketControllerIT extends PostgresIntegrationTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000500");
    private static final SessionId SESSION_ID =
            new SessionId(UUID.fromString("00000000-0000-0000-0000-000000000600"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedSession() {
        jdbc.update("""
                INSERT INTO m3_classroom.tasks
                    (id, course_id, mode, topic, duration_recommended_seconds,
                     duration_max_seconds, created_by, status)
                VALUES (?, ?, 'free', '荷塘月色', 900, 1200, ?, 'published')
                """, TASK_ID, TestFixtures.COURSE_ID, TestFixtures.TEACHER_ID);
        jdbc.update("""
                INSERT INTO m3_classroom.sessions
                    (id, session_type, task_id, course_id, student_account_id, mode, status)
                VALUES (?, 'task', ?, ?, ?, 'free', 'preparing')
                """, SESSION_ID.value(), TASK_ID, TestFixtures.COURSE_ID,
                TestFixtures.STUDENT_ID);
    }

    @Test
    void issuesTicketForOwnerWithExpiresAt() throws Exception {
        MvcResult result = mockMvc.perform(
                        asStudent(post("/api/v1/sessions/{id}/ws-ticket", SESSION_ID.value())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isNotEmpty())
                .andExpect(jsonPath("$.expires_at").isNotEmpty())
                .andExpect(jsonPath("$.expires_in_seconds").doesNotExist())
                .andReturn();

        String expiresAt = JsonPath.read(result.getResponse().getContentAsString(), "$.expires_at");
        assertThat(Duration.between(Instant.now(), Instant.parse(expiresAt)))
                .isBetween(Duration.ofSeconds(25), Duration.ofSeconds(35));
    }

    @Test
    void rejectsOtherStudentsSession() throws Exception {
        mockMvc.perform(asStudent2(post("/api/v1/sessions/{id}/ws-ticket", SESSION_ID.value())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WS_SESSION_FORBIDDEN"));
    }

    @Test
    void rejectsMissingIdentityHeaders() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{id}/ws-ticket", SESSION_ID.value()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsUnknownSession() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(asStudent(post("/api/v1/sessions/{id}/ws-ticket", unknown)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void rejectsFailedSession() throws Exception {
        jdbc.update("update m3_classroom.sessions set status = 'failed' where id = ?",
                SESSION_ID.value());

        mockMvc.perform(asStudent(post("/api/v1/sessions/{id}/ws-ticket", SESSION_ID.value())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_FAILED"));
    }

    private MockHttpServletRequestBuilder asStudent(MockHttpServletRequestBuilder builder) {
        return withIdentity(builder, TestFixtures.STUDENT_ID);
    }

    private MockHttpServletRequestBuilder asStudent2(MockHttpServletRequestBuilder builder) {
        return withIdentity(builder, TestFixtures.STUDENT2_ID);
    }

    private MockHttpServletRequestBuilder withIdentity(
            MockHttpServletRequestBuilder builder, UUID accountId) {
        return builder.header("X-Account-Id", accountId.toString())
                .header("X-School-Id", TestFixtures.SCHOOL_ID.toString())
                .header("X-Account-Type", "STUDENT");
    }
}
