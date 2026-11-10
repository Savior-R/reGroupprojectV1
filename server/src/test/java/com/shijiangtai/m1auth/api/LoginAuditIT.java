package com.shijiangtai.m1auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shijiangtai.m1auth.application.port.MailSender;
import com.shijiangtai.m1auth.infrastructure.mail.MockMailSender;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import java.util.List;
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
 * 登录审计（M1-T15、FR-M1-11 第 6 条、requirements §3.1 AuthLoginEvent、A25）。
 *
 * <p>断言四件事：
 * <ul>
 *   <li>密码 / 邮箱两种登录的成功与失败都写入 {@code auth_login_events}
 *   <li>{@code method} / {@code provider} / {@code success} / {@code ip} / {@code user_agent} 有值
 *   <li>失败事件不写 {@code account_id}，避免反向泄露账号存在性
 *   <li>表结构不含 token / 验证码 / secret 类列
 * </ul>
 */
@AutoConfigureMockMvc
class LoginAuditIT extends PostgresIntegrationTest {

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

    // ------------------------------------------------------------------ 用例

    @Test
    void passwordLoginSuccessWritesAuditEvent() throws Exception {
        String username = uniqueName("审计密码");
        String accountId = registerAndGetAccountId(username);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "junit-audit-ok")
                        .content(loginBody(username, PASSWORD)))
                .andExpect(status().isOk());

        Map<String, Object> event = latestByAccount(accountId);
        assertThat(event.get("method")).isEqualTo("password");
        assertThat(event.get("success")).isEqualTo(true);
        assertThat((String) event.get("ip")).contains("127.0.0.1");
        assertThat(event.get("user_agent")).isEqualTo("junit-audit-ok");
    }

    @Test
    void passwordLoginFailureWritesEventWithoutAccountId() throws Exception {
        String username = uniqueName("审计失败");
        registerAndGetAccountId(username);
        String marker = "junit-audit-fail-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", marker)
                        .content(loginBody(username, "WrongPass1!")))
                .andExpect(status().isUnauthorized());

        Map<String, Object> event = latestByUserAgent(marker);
        assertThat(event.get("method")).isEqualTo("password");
        assertThat(event.get("success")).isEqualTo(false);
        // 失败事件不带 account_id：FR-M1-11「不泄露账号存在性」
        assertThat(event.get("account_id")).isNull();
        assertThat((String) event.get("ip")).contains("127.0.0.1");
    }

    @Test
    void emailLoginSuccessWritesAuditEvent() throws Exception {
        String username = uniqueName("审计邮箱");
        String accountId = registerAndGetAccountId(username);
        String email = uniqueEmail();
        seedVerifiedEmail(username, email);
        String code = requestLoginCode(email);

        mockMvc.perform(post("/api/v1/auth/login/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "junit-audit-email-ok")
                        .content("""
                                {"email": "%s", "code": "%s"}
                                """.formatted(email, code)))
                .andExpect(status().isOk());

        Map<String, Object> event = latestByAccount(accountId);
        assertThat(event.get("method")).isEqualTo("email");
        assertThat(event.get("success")).isEqualTo(true);
        assertThat(event.get("user_agent")).isEqualTo("junit-audit-email-ok");
    }

    @Test
    void emailLoginFailureWritesEventWithoutAccountId() throws Exception {
        String username = uniqueName("审计邮箱失败");
        registerAndGetAccountId(username);
        String email = uniqueEmail();
        seedVerifiedEmail(username, email);
        requestLoginCode(email);
        String marker = "junit-audit-email-fail-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/auth/login/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", marker)
                        .content("""
                                {"email": "%s", "code": "000000"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized());

        Map<String, Object> event = latestByUserAgent(marker);
        assertThat(event.get("method")).isEqualTo("email");
        assertThat(event.get("success")).isEqualTo(false);
        assertThat(event.get("account_id")).isNull();
    }

    @Test
    void auditTableHoldsNoSecrets() {
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'm1_auth' and table_name = 'auth_login_events'
                """, Map.of(), String.class);

        assertThat(columns)
                .contains("method", "provider", "success", "ip", "user_agent", "created_at")
                .doesNotContain(
                        "token", "jwt", "code", "code_hash", "access_token",
                        "refresh_token", "secret", "password", "password_hash");
    }

    // ------------------------------------------------------------------ 辅助

    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@example.com";
    }

    private static String loginBody(String username, String password) {
        return """
                {"username": "%s", "password": "%s"}
                """.formatted(username, password);
    }

    /** 注册一个学生账号并返回 account_id。 */
    private String registerAndGetAccountId(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_type": "student",
                                  "username": "%s",
                                  "password": "%s",
                                  "school_id": "%s",
                                  "class_id": "%s"
                                }
                                """.formatted(username, PASSWORD, SCHOOL_ID, CLASS_ID)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.me.account_id");
    }

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
        return mockMail.lastCodeTo(email)
                .orElseThrow(() -> new AssertionError("未收到验证码邮件: " + email));
    }

    private Map<String, Object> latestByAccount(String accountId) {
        return jdbc.queryForMap("""
                select method, provider, success, account_id, host(ip) as ip, user_agent
                from m1_auth.auth_login_events
                where account_id = :accountId
                order by created_at desc, id desc
                limit 1
                """, Map.of("accountId", UUID.fromString(accountId)));
    }

    /** 失败事件没有 account_id，用唯一的 User-Agent 定位。 */
    private Map<String, Object> latestByUserAgent(String userAgent) {
        return jdbc.queryForMap("""
                select method, provider, success, account_id, host(ip) as ip, user_agent
                from m1_auth.auth_login_events
                where user_agent = :ua
                order by created_at desc, id desc
                limit 1
                """, Map.of("ua", userAgent));
    }
}
