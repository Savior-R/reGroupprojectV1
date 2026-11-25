package com.shijiangtai.m1auth.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/**
 * 我的登录记录（{@code GET /api/v1/auth/security/events}，03 §3.9 /settings/security）。
 *
 * <p>验证四点：未认证拒绝、只返回本人事件、{@code limit} 生效、字段不含敏感信息。
 */
@AutoConfigureMockMvc
class SecurityEventIT extends PostgresIntegrationTest {

    private static final String SCHOOL_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CLASS_ID = "00000000-0000-0000-0000-000000000200";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired
    private MockMvc mockMvc;

    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private String register(String username) throws Exception {
        String body = """
                {
                  "account_type": "student",
                  "username": "%s",
                  "password": "%s",
                  "school_id": "%s",
                  "class_id": "%s"
                }
                """.formatted(username, PASSWORD, SCHOOL_ID, CLASS_ID);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        return username;
    }

    /** 注册后再登录一次，产生一条 login/password 成功审计。 */
    private String registerAndLogin(String username) throws Exception {
        register(username);
        String body = """
                {"username": "%s", "password": "%s"}
                """.formatted(username, PASSWORD);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/auth/security/events"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsOwnLoginEventsWithSafeFields() throws Exception {
        String token = registerAndLogin(uniqueName("学生"));

        mockMvc.perform(get("/api/v1/auth/security/events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id", notNullValue()))
                .andExpect(jsonPath("$[0].event_type").value("login"))
                .andExpect(jsonPath("$[0].method").value("password"))
                .andExpect(jsonPath("$[0].success").value(true))
                .andExpect(jsonPath("$[0].created_at", notNullValue()))
                // 03 §3.10：审计视图不得出现 token / 验证码字段
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andExpect(jsonPath("$[0].code").doesNotExist());
    }

    @Test
    void limitCapsReturnedEvents() throws Exception {
        // 注意：registerAndLogin 返回的是 JWT，不是用户名；这里需保留用户名本身做二次登录
        String username = uniqueName("学生");
        registerAndLogin(username);
        String body = """
                {"username": "%s", "password": "%s"}
                """.formatted(username, PASSWORD);
        MvcResult second = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        String token = JsonPath.read(second.getResponse().getContentAsString(), "$.token");

        mockMvc.perform(get("/api/v1/auth/security/events")
                        .param("limit", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/v1/auth/security/events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void doesNotLeakOtherAccountsEvents() throws Exception {
        registerAndLogin(uniqueName("学生"));
        String otherToken = registerAndLogin(uniqueName("学生"));

        mockMvc.perform(get("/api/v1/auth/security/events")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    /** 账号安全页的核心用途：本人要能看到自己账号的失败登录尝试。 */
    @Test
    void failedPasswordLoginIsVisibleToOwner() throws Exception {
        String username = uniqueName("学生");
        String token = registerAndLogin(username);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "WrongPass1!"}
                                """.formatted(username)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/security/events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event_type").value("login"))
                .andExpect(jsonPath("$[0].method").value("password"))
                .andExpect(jsonPath("$[0].success").value(false));
    }
}
