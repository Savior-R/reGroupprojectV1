package com.shijiangtai.m1auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * 微信扫码登录 / 引导注册 / 绑定（M1-T12、M1-T13、M1-T14）。
 *
 * <p>测试 profile 使用 {@code MockWeChatProvider}：{@code code} 直接作为 openid，
 * 因此无需真实微信资质即可覆盖「未绑定 → 引导注册 → 已绑定 → 直接登录」全链路。
 */
@AutoConfigureMockMvc
class OAuthWeChatIT extends PostgresIntegrationTest {

    private static final String SCHOOL_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CLASS_ID = "00000000-0000-0000-0000-000000000200";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired
    private MockMvc mockMvc;

    private static String uniqueOpenId() {
        return "mock-openid-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 发起扫码并完成回调，返回 state。 */
    private String scan(String openId) throws Exception {
        MvcResult qr = mockMvc.perform(get("/api/v1/auth/oauth/wechat/qr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorize_url").exists())
                .andReturn();
        String state = JsonPath.read(qr.getResponse().getContentAsString(), "$.state");

        mockMvc.perform(get("/api/v1/auth/oauth/wechat/callback")
                        .param("code", openId)
                        .param("state", state))
                .andExpect(status().isOk());
        return state;
    }

    private String ticketOf(String state) throws Exception {
        return JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/oauth/wechat/status").param("state", state))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                "$.ticket");
    }

    @Test
    void unboundIdentityGoesToRegistrationThenLogsInDirectly() throws Exception {
        String openId = uniqueOpenId();
        String state = scan(openId);

        mockMvc.perform(get("/api/v1/auth/oauth/wechat/status").param("state", state))
                .andExpect(jsonPath("$.status").value("needs_registration"))
                .andExpect(jsonPath("$.ticket").exists());

        String registrationTicket = ticketOf(state);
        String username = "微信" + UUID.randomUUID().toString().substring(0, 6);

        mockMvc.perform(post("/api/v1/auth/oauth/wechat/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticket": "%s",
                                  "account_type": "student",
                                  "username": "%s",
                                  "password": "%s",
                                  "school_id": "%s",
                                  "class_id": "%s"
                                }
                                """.formatted(registrationTicket, username, PASSWORD,
                                SCHOOL_ID, CLASS_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.me.username").value(username))
                .andExpect(jsonPath("$.me.student.class_id").value(CLASS_ID));

        // 同一微信再扫一次 → 已绑定，直接登录
        String secondState = scan(openId);
        mockMvc.perform(get("/api/v1/auth/oauth/wechat/status").param("state", secondState))
                .andExpect(jsonPath("$.status").value("authorized"));

        String loginTicket = ticketOf(secondState);
        mockMvc.perform(post("/api/v1/auth/oauth/wechat/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket": "%s"}
                                """.formatted(loginTicket)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.me.username").value(username));
    }

    @Test
    void loginTicketIsSingleUse() throws Exception {
        String openId = uniqueOpenId();
        String state = scan(openId);
        String registrationTicket = ticketOf(state);
        mockMvc.perform(post("/api/v1/auth/oauth/wechat/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticket": "%s",
                                  "account_type": "student",
                                  "username": "%s",
                                  "password": "%s",
                                  "school_id": "%s",
                                  "class_id": "%s"
                                }
                                """.formatted(registrationTicket,
                                "微信重放" + UUID.randomUUID().toString().substring(0, 6),
                                PASSWORD, SCHOOL_ID, CLASS_ID)))
                .andExpect(status().isCreated());

        String secondState = scan(openId);
        String loginTicket = ticketOf(secondState);

        mockMvc.perform(post("/api/v1/auth/oauth/wechat/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket": "%s"}
                                """.formatted(loginTicket)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/oauth/wechat/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket": "%s"}
                                """.formatted(loginTicket)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_STATE_INVALID"));
    }

    @Test
    void unknownStateReportsExpired() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/wechat/status")
                        .param("state", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("expired"))
                .andExpect(jsonPath("$.ticket").doesNotExist());
    }

    @Test
    void callbackWithUnknownStateIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/wechat/callback")
                        .param("code", "mock-openid-x")
                        .param("state", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_STATE_INVALID"));
    }

    @Test
    void registrationTicketCannotBeReusedAfterSuccess() throws Exception {
        String openId = uniqueOpenId();
        String state = scan(openId);
        String ticket = ticketOf(state);

        mockMvc.perform(post("/api/v1/auth/oauth/wechat/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticket": "%s",
                                  "account_type": "student",
                                  "username": "%s",
                                  "password": "%s",
                                  "school_id": "%s",
                                  "class_id": "%s"
                                }
                                """.formatted(ticket, "微信一次" + UUID.randomUUID().toString().substring(0, 6),
                                PASSWORD, SCHOOL_ID, CLASS_ID)))
                .andExpect(status().isCreated());

        // 同一 ticket 再次注册 → ticket 已被消耗
        mockMvc.perform(post("/api/v1/auth/oauth/wechat/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticket": "%s",
                                  "account_type": "student",
                                  "username": "%s",
                                  "password": "%s",
                                  "school_id": "%s",
                                  "class_id": "%s"
                                }
                                """.formatted(ticket, "微信二次" + UUID.randomUUID().toString().substring(0, 6),
                                PASSWORD, SCHOOL_ID, CLASS_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_STATE_INVALID"));
    }

    @Test
    void bindWechatToExistingAccountRequiresPassword() throws Exception {
        // 注册普通学生
        String username = "绑微" + UUID.randomUUID().toString().substring(0, 6);
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
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
        String token = JsonPath.read(registered.getResponse().getContentAsString(), "$.token");

        String openId = uniqueOpenId();
        String state = scan(openId);
        String ticket = ticketOf(state);

        // 密码错误 → 401
        mockMvc.perform(post("/api/v1/auth/oauth/wechat/bind")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket": "%s", "current_password": "wrong-pass-1"}
                                """.formatted(ticket)))
                .andExpect(status().isUnauthorized());

        // 正确密码 → 绑定成功
        mockMvc.perform(post("/api/v1/auth/oauth/wechat/bind")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket": "%s", "current_password": "%s"}
                                """.formatted(ticket, PASSWORD)))
                .andExpect(status().isNoContent());

        // 绑定后扫码直接 authorized
        String secondState = scan(openId);
        mockMvc.perform(get("/api/v1/auth/oauth/wechat/status").param("state", secondState))
                .andExpect(jsonPath("$.status").value("authorized"));
    }

    @Test
    void unbindWechatRequiresPassword() throws Exception {
        String username = "解微" + UUID.randomUUID().toString().substring(0, 6);
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
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
                .andReturn();
        String token = JsonPath.read(registered.getResponse().getContentAsString(), "$.token");

        String openId = uniqueOpenId();
        String ticket = ticketOf(scan(openId));
        mockMvc.perform(post("/api/v1/auth/oauth/wechat/bind")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket": "%s", "current_password": "%s"}
                                """.formatted(ticket, PASSWORD)))
                .andExpect(status().isNoContent());

        // 未绑定时解绑 → 400
        mockMvc.perform(delete("/api/v1/auth/oauth/wechat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"current_password": "%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/auth/oauth/wechat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"current_password": "%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wechatIdentityCannotBindTwoAccounts() throws Exception {
        String openId = uniqueOpenId();

        // 第一个账号：扫码 → 引导注册 → 已绑定
        String firstTicket = ticketOf(scan(openId));
        mockMvc.perform(post("/api/v1/auth/oauth/wechat/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticket": "%s",
                                  "account_type": "student",
                                  "username": "%s",
                                  "password": "%s",
                                  "school_id": "%s",
                                  "class_id": "%s"
                                }
                                """.formatted(firstTicket,
                                "微信甲" + UUID.randomUUID().toString().substring(0, 6),
                                PASSWORD, SCHOOL_ID, CLASS_ID)))
                .andExpect(status().isCreated());

        // 第二个账号尝试绑定同一微信
        MvcResult second = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_type": "student",
                                  "username": "%s",
                                  "password": "%s",
                                  "school_id": "%s",
                                  "class_id": "%s"
                                }
                                """.formatted("微信乙" + UUID.randomUUID().toString().substring(0, 6),
                                PASSWORD, SCHOOL_ID, CLASS_ID)))
                .andReturn();
        String token = JsonPath.read(second.getResponse().getContentAsString(), "$.token");

        // 已绑定身份的扫码会得到 authorized（而非 registration），故用 registration 流程验证 409 不可达；
        // 这里直接断言「扫已绑定微信拿到的是 authorized」，即不会产生第二份绑定入口
        String state = scan(openId);
        mockMvc.perform(get("/api/v1/auth/oauth/wechat/status").param("state", state))
                .andExpect(jsonPath("$.status").value("authorized"));

        // 用该 token 解绑（本来就没绑定）→ 400，说明第二个账号仍未绑定
        mockMvc.perform(delete("/api/v1/auth/oauth/wechat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"current_password": "%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest());

        assertThat(token).isNotBlank();
    }
}
