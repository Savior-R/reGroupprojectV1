package com.shijiangtai.m1auth.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * 内部认证端点（02 §2.2）：Nginx {@code /_auth} 的落地实现。
 *
 * <p>覆盖：无令牌 / 错令牌 / 无 Bearer / 有效 JWT 回传身份头 / 无效 JWT。
 */
@AutoConfigureMockMvc
class InternalAuthIT extends PostgresIntegrationTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Autowired
    private MockMvc mockMvc;

    private String registerAndGetToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_type": "student",
                                  "username": "%s",
                                  "password": "Passw0rd!",
                                  "school_id": "00000000-0000-0000-0000-000000000001",
                                  "class_id": "00000000-0000-0000-0000-000000000200"
                                }
                                """.formatted("内部" + UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    @Test
    void rejectsRequestWithoutInternalToken() throws Exception {
        mockMvc.perform(get("/internal/auth/verify"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongInternalToken() throws Exception {
        mockMvc.perform(get("/internal/auth/verify")
                        .header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMissingBearer() throws Exception {
        mockMvc.perform(get("/internal/auth/verify")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsIdentityHeadersForValidJwt() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(get("/internal/auth/verify")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Account-Id"))
                .andExpect(header().string("X-Account-Type", "student"))
                .andExpect(header().string("X-School-Id",
                        "00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void rejectsGarbageJwt() throws Exception {
        mockMvc.perform(get("/internal/auth/verify")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalPathIsNotReachableWithoutToken() throws Exception {
        // 模拟 Nginx deny all 之外的第一道防线：拿不到令牌就进不去
        mockMvc.perform(get("/internal/auth/verify")
                        .header("X-Account-Id", "00000000-0000-0000-0000-000000000020")
                        .header("X-Account-Type", "student"))
                .andExpect(status().isUnauthorized());
    }
}
