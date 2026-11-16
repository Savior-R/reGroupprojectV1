package com.shijiangtai.m1auth.api;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 多角色（教师兼管理员）集成验证（FR-M1-12、V14 account_roles）。
 *
 * <p>断言：
 * <ul>
 *   <li>教师被授予 admin 后，重新登录即可访问管理端，且主身份仍是 teacher
 *   <li>撤销后重新登录即失去管理端访问
 *   <li>角色是 token 快照：未重新登录的旧 token 权限不变（明确的取舍）
 *   <li>只能给本校教师授予；不能给自己、不能给学生、不能跨校
 * </ul>
 */
@AutoConfigureMockMvc
class TeacherRoleIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "Passw0rd!";
    /** test-upstream 里的既有学校与专业，用于「跨校」用例。 */
    private static final String OTHER_SCHOOL = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER_MAJOR = "00000000-0000-0000-0000-000000000300";

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ 授权生效

    @Test
    void grantedTeacherCanReachAdminEndpoints() throws Exception {
        Admin admin = newAdmin();
        Teacher teacher = newTeacher(admin);

        mockMvc.perform(get("/api/v1/admin/majors")
                        .header("Authorization", "Bearer " + teacher.token))
                .andExpect(status().isForbidden());

        grantAdmin(admin.token, teacher.accountId, true);

        String relogin = login(teacher.username);
        mockMvc.perform(get("/api/v1/admin/majors").header("Authorization", "Bearer " + relogin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/teachers").header("Authorization", "Bearer " + relogin))
                .andExpect(status().isOk());

        // 主身份不变：仍是教师，roles 里多出 admin
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + relogin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account_type").value("teacher"))
                .andExpect(jsonPath("$.roles", hasItem("teacher")))
                .andExpect(jsonPath("$.roles", hasItem("admin")));
    }

    @Test
    void revokedTeacherLosesAdminAccess() throws Exception {
        Admin admin = newAdmin();
        Teacher teacher = newTeacher(admin);

        grantAdmin(admin.token, teacher.accountId, true);
        String withAdmin = login(teacher.username);
        mockMvc.perform(get("/api/v1/admin/majors").header("Authorization", "Bearer " + withAdmin))
                .andExpect(status().isOk());

        grantAdmin(admin.token, teacher.accountId, false);
        String withoutAdmin = login(teacher.username);
        mockMvc.perform(get("/api/v1/admin/majors")
                        .header("Authorization", "Bearer " + withoutAdmin))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + withoutAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("teacher")));
    }

    @Test
    void tokenIsPermissionSnapshotUntilRelogin() throws Exception {
        Admin admin = newAdmin();
        Teacher teacher = newTeacher(admin);

        grantAdmin(admin.token, teacher.accountId, true);

        // 未重新登录：旧 token 的角色集合不变，仍被拒绝
        mockMvc.perform(get("/api/v1/admin/majors")
                        .header("Authorization", "Bearer " + teacher.token))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 防提权

    @Test
    void cannotGrantToSelf() throws Exception {
        Admin admin = newAdmin();

        mockMvc.perform(put("/api/v1/admin/teachers/{id}/roles", admin.accountId)
                        .header("Authorization", "Bearer " + admin.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admin\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void cannotGrantToStudent() throws Exception {
        Admin admin = newAdmin();
        String studentId = newStudent(admin);

        mockMvc.perform(put("/api/v1/admin/teachers/{id}/roles", studentId)
                        .header("Authorization", "Bearer " + admin.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admin\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void cannotGrantToTeacherOfAnotherSchool() throws Exception {
        Admin admin = newAdmin();
        // 既有学校下的教师，与 admin 新建的学校不同
        String otherTeacherId = registerTeacher(OTHER_SCHOOL, OTHER_MAJOR);

        mockMvc.perform(put("/api/v1/admin/teachers/{id}/roles", otherTeacherId)
                        .header("Authorization", "Bearer " + admin.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admin\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SCHOOL_ACCESS"));
    }

    @Test
    void plainTeacherCannotGrantRoles() throws Exception {
        Admin admin = newAdmin();
        Teacher first = newTeacher(admin);
        Teacher second = newTeacher(admin);

        mockMvc.perform(put("/api/v1/admin/teachers/{id}/roles", second.accountId)
                        .header("Authorization", "Bearer " + first.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admin\":true}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 列表

    @Test
    void teacherListShowsRoles() throws Exception {
        Admin admin = newAdmin();
        Teacher teacher = newTeacher(admin);
        grantAdmin(admin.token, teacher.accountId, true);

        mockMvc.perform(get("/api/v1/admin/teachers")
                        .header("Authorization", "Bearer " + admin.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.account_id=='" + teacher.accountId + "')].roles[*]",
                        hasItem("admin")));
    }

    // ------------------------------------------------------------------ 辅助

    private record Admin(String token, String schoolId, String accountId, String username) {
    }

    private record Teacher(String token, String accountId, String username) {
    }

    /** 注册管理员并建校，返回带 school_id 的 token。 */
    private Admin newAdmin() throws Exception {
        String username = uniqueName("角色管理员");
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_type":"admin","username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        String registeredBody = registered.getResponse().getContentAsString();
        String accountId = JsonPath.read(registeredBody, "$.me.account_id");
        String token = JsonPath.read(registeredBody, "$.token");

        MvcResult created = mockMvc.perform(post("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(username + "中学")))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        return new Admin(
                JsonPath.read(body, "$.token"),
                JsonPath.read(body, "$.school.school_id"),
                accountId,
                username);
    }

    /** 在本校建一个专业后注册教师。 */
    private Teacher newTeacher(Admin admin) throws Exception {
        String username = uniqueName("角色教师");
        MvcResult major = mockMvc.perform(post("/api/v1/admin/majors")
                        .header("Authorization", "Bearer " + admin.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(uniqueName("专业"))))
                .andExpect(status().isCreated())
                .andReturn();
        String majorId = JsonPath.read(major.getResponse().getContentAsString(), "$.id");

        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_type":"teacher","username":"%s","password":"%s",
                                 "school_id":"%s","major_ids":["%s"]}
                                """.formatted(username, PASSWORD, admin.schoolId, majorId)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = registered.getResponse().getContentAsString();
        return new Teacher(
                JsonPath.read(body, "$.token"),
                JsonPath.read(body, "$.me.account_id"),
                username);
    }

    /** 在本校建专业 + 班级后注册学生，返回账号 id。 */
    private String newStudent(Admin admin) throws Exception {
        MvcResult major = mockMvc.perform(post("/api/v1/admin/majors")
                        .header("Authorization", "Bearer " + admin.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(uniqueName("专业"))))
                .andExpect(status().isCreated())
                .andReturn();
        String majorId = JsonPath.read(major.getResponse().getContentAsString(), "$.id");

        MvcResult created = mockMvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", "Bearer " + admin.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"major_id":"%s","grade":"2024","name":"%s"}
                                """.formatted(majorId, uniqueName("班"))))
                .andExpect(status().isCreated())
                .andReturn();
        String classId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_type":"student","username":"%s","password":"%s",
                                 "school_id":"%s","class_id":"%s"}
                                """.formatted(
                                uniqueName("角色学生"), PASSWORD, admin.schoolId, classId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(registered.getResponse().getContentAsString(), "$.me.account_id");
    }

    private String registerTeacher(String schoolId, String majorId) throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_type":"teacher","username":"%s","password":"%s",
                                 "school_id":"%s","major_ids":["%s"]}
                                """.formatted(
                                uniqueName("跨校教师"), PASSWORD, schoolId, majorId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(registered.getResponse().getContentAsString(), "$.me.account_id");
    }

    private void grantAdmin(String adminToken, String teacherId, boolean grant) throws Exception {
        var request = put("/api/v1/admin/teachers/{id}/roles", teacherId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"admin\":%s}".formatted(grant));
        if (grant) {
            mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles", hasItem("admin")));
        } else {
            mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.not(hasItem("admin"))));
        }
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }
}
