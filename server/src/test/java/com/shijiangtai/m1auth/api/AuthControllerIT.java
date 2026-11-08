package com.shijiangtai.m1auth.api;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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
 * 注册 / 密码登录 / me 闭环（M1-T01、M1-T03、M1-T04）。
 * 依赖 {@code db/test-upstream.sql} 预置的学校 / 专业 / 班级。
 */
@AutoConfigureMockMvc
class AuthControllerIT extends PostgresIntegrationTest {

    private static final String SCHOOL_ID = "00000000-0000-0000-0000-000000000001";
    private static final String MAJOR_ID = "00000000-0000-0000-0000-000000000300";
    private static final String CLASS_ID = "00000000-0000-0000-0000-000000000200";

    @Autowired
    private MockMvc mockMvc;

    /** 用户名即真实姓名且平台唯一，测试用随机后缀避免跨用例冲突。 */
    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private String registerStudent(String username, String password) throws Exception {
        String body = """
                {
                  "account_type": "student",
                  "username": "%s",
                  "password": "%s",
                  "school_id": "%s",
                  "class_id": "%s"
                }
                """.formatted(username, password, SCHOOL_ID, CLASS_ID);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.expires_at", notNullValue()))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    @Test
    void registerStudentReturnsTokenAndClassProfile() throws Exception {
        String username = uniqueName("学生");
        String body = """
                {
                  "account_type": "student",
                  "username": "%s",
                  "password": "Passw0rd!",
                  "school_id": "%s",
                  "class_id": "%s"
                }
                """.formatted(username, SCHOOL_ID, CLASS_ID);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.me.username").value(username))
                .andExpect(jsonPath("$.me.account_type").value("student"))
                .andExpect(jsonPath("$.me.school_id").value(SCHOOL_ID))
                .andExpect(jsonPath("$.me.student.class_id").value(CLASS_ID))
                .andExpect(jsonPath("$.me.student.grade").value("2024"))
                .andExpect(jsonPath("$.me.student.major_name").value("测试专业"));
    }

    @Test
    void registerAdminHasNoSchoolUntilCreated() throws Exception {
        String body = """
                {
                  "account_type": "admin",
                  "username": "%s",
                  "password": "Passw0rd!"
                }
                """.formatted(uniqueName("管理员"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.me.account_type").value("admin"))
                .andExpect(jsonPath("$.me.school_id").value(nullValue()));
    }

    @Test
    void registerTeacherKeepsSelectedMajors() throws Exception {
        String body = """
                {
                  "account_type": "teacher",
                  "username": "%s",
                  "password": "Passw0rd!",
                  "school_id": "%s",
                  "major_ids": ["%s"]
                }
                """.formatted(uniqueName("教师"), SCHOOL_ID, MAJOR_ID);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.me.account_type").value("teacher"))
                .andExpect(jsonPath("$.me.teacher.majors[0].id").value(MAJOR_ID))
                .andExpect(jsonPath("$.me.teacher.majors[0].name").value("测试专业"));
    }

    @Test
    void duplicateUsernameIsRejected() throws Exception {
        String username = uniqueName("重复");
        registerStudent(username, "Passw0rd!");

        String body = """
                {
                  "account_type": "student",
                  "username": "%s",
                  "password": "Passw0rd!",
                  "school_id": "%s",
                  "class_id": "%s"
                }
                """.formatted(username, SCHOOL_ID, CLASS_ID);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void registerRejectsShortPassword() throws Exception {
        String body = """
                {
                  "account_type": "student",
                  "username": "%s",
                  "password": "1234567",
                  "school_id": "%s",
                  "class_id": "%s"
                }
                """.formatted(uniqueName("短密码"), SCHOOL_ID, CLASS_ID);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void loginReturnsTokenAndWrongPasswordIsIndistinguishable() throws Exception {
        String username = uniqueName("登录");
        registerStudent(username, "Passw0rd!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "Passw0rd!"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()));

        // 密码错误与账号不存在必须返回同一 code / message（FR-M1-02、M1-T04）
        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "WrongPass1"}
                                """.formatted(username)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn().getResponse().getContentAsString();

        String unknownAccount = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "no-such-user-%s", "password": "WrongPass1"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn().getResponse().getContentAsString();

        String wrongMessage = JsonPath.read(wrongPassword, "$.message");
        String unknownMessage = JsonPath.read(unknownAccount, "$.message");
        org.assertj.core.api.Assertions.assertThat(wrongMessage).isEqualTo(unknownMessage);
    }

    @Test
    void meRequiresTokenAndReturnsCurrentAccount() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());

        String username = uniqueName("本人");
        String token = registerStudent(username, "Passw0rd!");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.account_type").value("student"))
                .andExpect(jsonPath("$.email_verified").value(false));
    }
}
