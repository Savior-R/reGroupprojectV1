package com.shijiangtai.m1auth.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 表驱动授权（RBAC）集成验证（FR-M1-07、02 §4.1.1）。
 *
 * <p>断言四件事：
 * <ul>
 *   <li>管理员可访问 {@code /api/v1/admin/**}（权限码 admin:school / admin:org 命中）
 *   <li>教师 / 学生访问同一路径返回 403（权限码不在其角色集合内）
 *   <li>三个角色都能访问 {@code /auth/me}（权限码 login）
 *   <li>{@code api_permissions} 里没有的路径 fail closed（403），而非默认放行
 * </ul>
 */
@AutoConfigureMockMvc
class RbacIT extends PostgresIntegrationTest {

    private static final String SCHOOL_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CLASS_ID = "00000000-0000-0000-0000-000000000200";
    private static final String MAJOR_ID = "00000000-0000-0000-0000-000000000300";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ 用例

    @Test
    void adminCanReachAdminEndpoints() throws Exception {
        // 必须先建校：未建校时业务层会抛 FORBIDDEN，掩盖 RBAC 的判定结果
        String token = registerAdminWithSchool(uniqueName("RBAC管理员"));

        mockMvc.perform(get("/api/v1/admin/majors").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/teachers").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void teacherIsForbiddenOnAdminEndpoints() throws Exception {
        String token = register("teacher", uniqueName("RBAC教师"));

        mockMvc.perform(get("/api/v1/admin/majors").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/teachers").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentIsForbiddenOnAdminEndpoints() throws Exception {
        String token = register("student", uniqueName("RBAC学生"));

        mockMvc.perform(get("/api/v1/admin/school").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void allRolesCanReadOwnProfile() throws Exception {
        for (String type : new String[] {"admin", "teacher", "student"}) {
            String token = register(type, uniqueName("RBAC" + type));
            mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void unknownRouteFailsClosed() throws Exception {
        String token = register("admin", uniqueName("RBAC未知路由"));

        // 该路径在 api_permissions 中没有规则；fail closed 应返回 403 而不是放行
        mockMvc.perform(get("/api/v1/admin/not-a-real-endpoint")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedStillGets401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/majors"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 辅助

    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }

    /** 注册管理员并建校，返回带 school_id 的新 token。 */
    private String registerAdminWithSchool(String username) throws Exception {
        String token = register("admin", username);
        MvcResult result = mockMvc.perform(post("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(username + "中学")))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** 按账号类型注册并返回 token；学生需要 class_id，教师需要 major_ids。 */
    private String register(String type, String username) throws Exception {
        String body = switch (type) {
            case "student" -> """
                    {"account_type":"student","username":"%s","password":"%s",
                     "school_id":"%s","class_id":"%s"}
                    """.formatted(username, PASSWORD, SCHOOL_ID, CLASS_ID);
            case "teacher" -> """
                    {"account_type":"teacher","username":"%s","password":"%s",
                     "school_id":"%s","major_ids":["%s"]}
                    """.formatted(username, PASSWORD, SCHOOL_ID, MAJOR_ID);
            default -> """
                    {"account_type":"admin","username":"%s","password":"%s"}
                    """.formatted(username, PASSWORD);
        };
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
