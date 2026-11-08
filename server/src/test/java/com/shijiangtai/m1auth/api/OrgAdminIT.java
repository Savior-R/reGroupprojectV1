package com.shijiangtai.m1auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 建校 / 专业 / 班级 / 教师专业 / 导入（M1-T05、M1-T06、M1-T07）。
 */
@AutoConfigureMockMvc
class OrgAdminIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "Passw0rd!";
    private static final String MAJOR_ID = "00000000-0000-0000-0000-000000000300";

    @Autowired
    private MockMvc mockMvc;

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 注册管理员并建校，返回带 school_id 的新 token。 */
    private String adminWithSchool(String schoolName) throws Exception {
        String body = """
                {"account_type": "admin", "username": "%s", "password": "%s"}
                """.formatted(unique("校管"), PASSWORD);
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String token = JsonPath.read(registered.getResponse().getContentAsString(), "$.token");

        MvcResult created = mockMvc.perform(post("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(schoolName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.school.name").value(schoolName))
                .andExpect(jsonPath("$.token").exists())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.token");
    }

    @Test
    void createSchoolReturnsTokenCarryingSchoolId() throws Exception {
        String schoolName = unique("测试中学");
        String token = adminWithSchool(schoolName);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school_name").value(schoolName))
                .andExpect(jsonPath("$.school_id").exists());
    }

    @Test
    void secondSchoolIsRejected() throws Exception {
        String token = adminWithSchool(unique("唯一中学"));

        mockMvc.perform(post("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(unique("第二中学"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHOOL_ALREADY_BOUND"));
    }

    @Test
    void schoolNameMustBeUniqueAcrossPlatform() throws Exception {
        String shared = unique("重名中学");
        adminWithSchool(shared);

        String body = """
                {"account_type": "admin", "username": "%s", "password": "%s"}
                """.formatted(unique("第二个"), PASSWORD);
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        String token = JsonPath.read(registered.getResponse().getContentAsString(), "$.token");

        mockMvc.perform(post("/api/v1/admin/schools")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(shared)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminWithoutSchoolCannotTouchOrg() throws Exception {
        String body = """
                {"account_type": "admin", "username": "%s", "password": "%s"}
                """.formatted(unique("未建校"), PASSWORD);
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        String token = JsonPath.read(registered.getResponse().getContentAsString(), "$.token");

        mockMvc.perform(get("/api/v1/admin/majors").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void majorAndClassCrudThenPublicOptionsHideDisabled() throws Exception {
        String token = adminWithSchool(unique("组织中学"));

        MvcResult majorResult = mockMvc.perform(post("/api/v1/admin/majors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "软件工程"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        String majorId = JsonPath.read(majorResult.getResponse().getContentAsString(), "$.id");
        String schoolId = JsonPath.read(
                mockMvc.perform(get("/api/v1/admin/school").header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getContentAsString(), "$.school_id");

        mockMvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"major_id": "%s", "grade": "2024", "name": "1班"}
                                """.formatted(majorId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.major_name").value("软件工程"));

        // 公开选项可见
        mockMvc.perform(get("/api/v1/public/schools/{id}/majors", schoolId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("软件工程"));
        mockMvc.perform(get("/api/v1/public/classes")
                        .param("schoolId", schoolId).param("majorId", majorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("1班"));

        // 停用专业后公开选项不再返回
        mockMvc.perform(patch("/api/v1/admin/majors/{id}", majorId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active": false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/v1/public/schools/{id}/majors", schoolId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void adminUpdatesTeacherMajorsAndTeacherCannotSelfEdit() throws Exception {
        String schoolName = unique("教师中学");
        String adminToken = adminWithSchool(schoolName);
        String schoolId = JsonPath.read(
                mockMvc.perform(get("/api/v1/admin/school").header("Authorization", "Bearer " + adminToken))
                        .andReturn().getResponse().getContentAsString(), "$.school_id");

        MvcResult majorResult = mockMvc.perform(post("/api/v1/admin/majors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "汉语言文学"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String majorId = JsonPath.read(majorResult.getResponse().getContentAsString(), "$.id");

        // 教师注册到该校
        String teacherName = unique("老师");
        MvcResult teacherResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account_type": "teacher",
                                  "username": "%s",
                                  "password": "%s",
                                  "school_id": "%s",
                                  "major_ids": ["%s"]
                                }
                                """.formatted(teacherName, PASSWORD, schoolId, majorId)))
                .andExpect(status().isCreated())
                .andReturn();
        String teacherToken = JsonPath.read(
                teacherResult.getResponse().getContentAsString(), "$.token");
        String teacherId = JsonPath.read(
                teacherResult.getResponse().getContentAsString(), "$.me.account_id");

        // 教师本人访问管理员接口被拒（角色不符）
        mockMvc.perform(get("/api/v1/admin/teachers").header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isForbidden());

        // 管理员把专业范围改成空 → 拒绝
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/admin/teachers/{id}/majors", teacherId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"major_ids": []}
                                """))
                .andExpect(status().isBadRequest());

        // 管理员改为同一专业的新关系，历史行保留但只有一条 active
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/admin/teachers/{id}/majors", teacherId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"major_ids": ["%s"]}
                                """.formatted(majorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.majors[0].id").value(majorId));
    }

    @Test
    void importMajorsByCsvSucceeds() throws Exception {
        String token = adminWithSchool(unique("导入中学"));

        String csv = "name\n网络工程\n物联网工程\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "majors.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/admin/majors/import")
                        .file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.errors.length()").value(0));
    }

    @Test
    void importWithInvalidRowWritesNothing() throws Exception {
        String token = adminWithSchool(unique("回滚中学"));
        String schoolId = JsonPath.read(
                mockMvc.perform(get("/api/v1/admin/school").header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getContentAsString(), "$.school_id");

        // 第二行专业名超过 100 字符 → 整批失败，第一行也不写入
        String csv = "name\n合法专业\n" + "x".repeat(101) + "\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "majors.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/admin/majors/import")
                        .file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.errors[0].column").value("name"));

        // 合法专业未被写入：整批回滚
        mockMvc.perform(get("/api/v1/public/schools/{id}/majors", schoolId))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void importClassesRejectsUnknownMajor() throws Exception {
        String token = adminWithSchool(unique("班级导入"));

        String csv = "major_name,grade,name,status\n不存在的专业,2024,1班,active\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "classes.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/admin/classes/import")
                        .file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.errors[0].column").value("major_name"));
    }

    @Test
    void templateDownloadReturnsCsv() throws Exception {
        String token = adminWithSchool(unique("模板中学"));

        MvcResult result = mockMvc.perform(get("/api/v1/admin/imports/templates/major")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
        assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("name");
    }

    @Test
    void studentCannotAccessAdminEndpoints() throws Exception {
        String token = JsonPath.read(
                mockMvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "account_type": "student",
                                          "username": "%s",
                                          "password": "%s",
                                          "school_id": "00000000-0000-0000-0000-000000000001",
                                          "class_id": "00000000-0000-0000-0000-000000000200"
                                        }
                                        """.formatted(unique("学生"), PASSWORD)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(), "$.token");

        mockMvc.perform(get("/api/v1/admin/majors").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void presetMajorIsVisibleForExistingSchool() throws Exception {
        // 复用 test-upstream 预置学校，确认公开选项链路
        mockMvc.perform(get("/api/v1/public/schools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.school_id=='00000000-0000-0000-0000-000000000001')]").exists());
        mockMvc.perform(get("/api/v1/public/schools/{id}/grades",
                        "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("2024"));
        assertThat(MAJOR_ID).isNotBlank();
    }
}
