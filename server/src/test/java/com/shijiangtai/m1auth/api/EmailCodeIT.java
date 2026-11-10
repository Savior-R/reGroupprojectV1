package com.shijiangtai.m1auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shijiangtai.m1auth.application.port.MailSender;
import com.shijiangtai.m1auth.infrastructure.mail.MockMailSender;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 邮箱验证码全链路（M1-T11、M1-T15）。
 *
 * <p>限流按邮箱计数且不区分 purpose（60 秒重发），所以需要「已绑定邮箱」的用例
 * 用 SQL 预置状态，避免同一用例内连发两次码被 429 拦住。
 */
@AutoConfigureMockMvc
class EmailCodeIT extends PostgresIntegrationTest {

    private static final String SCHOOL_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CLASS_ID = "00000000-0000-0000-0000-000000000200";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MailSender mailSender;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private MockMailSender mockMail;

    @BeforeEach
    void setUp() {
        mockMail = (MockMailSender) mailSender;
        mockMail.clear();
    }

    private static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@example.com";
    }

    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
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
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** 直接写入已验证邮箱，跳过绑定流程（绑定流程单独用例覆盖）。 */
    private void seedVerifiedEmail(String username, String email) {
        jdbc.update("""
                update m1_auth.accounts set email = :email, email_verified_at = now()
                where username = :username
                """, Map.of("email", email, "username", username));
    }

    private String requestLoginCode(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "purpose": "login"}
                                """.formatted(email)))
                .andExpect(status().isAccepted());
        return mockMail.lastCodeTo(email).orElseThrow(
                () -> new AssertionError("未收到验证码邮件: " + email));
    }

    @Test
    void bindsEmailThroughApiAndMarksVerified() throws Exception {
        String username = uniqueName("绑定");
        String token = register(username);
        String email = uniqueEmail();

        mockMvc.perform(post("/api/v1/auth/email/bind/code")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "purpose": "bind"}
                                """.formatted(email)))
                .andExpect(status().isAccepted());

        String code = mockMail.lastCodeTo(email).orElseThrow();
        mockMvc.perform(post("/api/v1/auth/email/bind")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "code": "%s", "current_password": "%s"}
                                """.formatted(email, code, PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.email_verified").value(true));
    }

    @Test
    void bindingRequiresCorrectCurrentPassword() throws Exception {
        String token = register(uniqueName("绑定校验"));
        String email = uniqueEmail();

        mockMvc.perform(post("/api/v1/auth/email/bind/code")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s"}
                                """.formatted(email)))
                .andExpect(status().isAccepted());
        String code = mockMail.lastCodeTo(email).orElseThrow();

        mockMvc.perform(post("/api/v1/auth/email/bind")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "code": "%s", "current_password": "wrong-pass-1"}
                                """.formatted(email, code)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emailCodeLoginIssuesTokenWithSameClaims() throws Exception {
        String username = uniqueName("邮箱登录");
        String email = uniqueEmail();
        register(username);
        seedVerifiedEmail(username, email);

        String code = requestLoginCode(email);

        mockMvc.perform(post("/api/v1/auth/login/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "code": "%s"}
                                """.formatted(email, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.me.username").value(username))
                .andExpect(jsonPath("$.me.account_type").value("student"))
                .andExpect(jsonPath("$.me.school_id").value(SCHOOL_ID));
    }

    @Test
    void consumedCodeCannotBeReplayed() throws Exception {
        String username = uniqueName("重放");
        String email = uniqueEmail();
        register(username);
        seedVerifiedEmail(username, email);
        String code = requestLoginCode(email);

        mockMvc.perform(post("/api/v1/auth/login/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "code": "%s"}
                                """.formatted(email, code)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "code": "%s"}
                                """.formatted(email, code)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void wrongCodeIsRejected() throws Exception {
        String username = uniqueName("错码");
        String email = uniqueEmail();
        register(username);
        seedVerifiedEmail(username, email);
        String code = requestLoginCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        mockMvc.perform(post("/api/v1/auth/login/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "code": "%s"}
                                """.formatted(email, wrong)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resendWithinSixtySecondsIsRateLimited() throws Exception {
        String email = uniqueEmail();
        String body = """
                {"email": "%s", "purpose": "login"}
                """.formatted(email);

        mockMvc.perform(post("/api/v1/auth/email/code")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/auth/email/code")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void sendAcceptsUnknownEmailToAvoidLeakage() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "purpose": "login"}
                                """.formatted(uniqueEmail())))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/auth/login/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "code": "123456"}
                                """.formatted(uniqueEmail())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unbindRequiresCurrentPasswordAndClearsEmail() throws Exception {
        String username = uniqueName("解绑");
        String email = uniqueEmail();
        String token = register(username);
        seedVerifiedEmail(username, email);

        mockMvc.perform(delete("/api/v1/auth/email")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"current_password": "wrong-pass-1"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/v1/auth/email")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"current_password": "%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.email_verified").value(false));
    }

    @Test
    void bindCodeRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/bind/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s"}
                                """.formatted(uniqueEmail())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mockMailboxCapturesCodeWithSixDigits() throws Exception {
        String email = uniqueEmail();
        String code = requestLoginCode(email);

        assertThat(code).hasSize(6).matches("\\d{6}");
        assertThat(mockMail.sentMessages()).isNotEmpty();
    }
}
