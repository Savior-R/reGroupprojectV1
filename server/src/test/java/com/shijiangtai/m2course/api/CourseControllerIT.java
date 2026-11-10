package com.shijiangtai.m2course.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shijiangtai.m2course.application.port.CourseRepository;
import com.shijiangtai.m2course.domain.Course;
import com.shijiangtai.m2course.domain.MajorId;
import com.shijiangtai.m2course.persistence.M2PostgresIT;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** M2 API IT：建课 / 名称唯一 / 角色与跨校 / 老师组 / 学生加入退出 / 可见性 / 错误码。 */
@AutoConfigureMockMvc
class CourseControllerIT extends M2PostgresIT {
    private static final Instant FUTURE = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courseRepository;

    @Test
    void createCourseReturnsSnakeCaseDetailAndCreatorInTeacherGroup() throws Exception {
        String body = """
                {"name": "M2 建课", "major_ids": ["%s"], "end_at": "2030-01-01T00:00:00Z"}
                """.formatted(MAJOR);

        mockMvc.perform(as(post("/api/v1/courses"), TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("M2 建课"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.school_id").value(SCHOOL.toString()))
                .andExpect(jsonPath("$.created_by").value(TEACHER.toString()))
                .andExpect(jsonPath("$.majors[0].id").value(MAJOR.toString()))
                .andExpect(jsonPath("$.teachers[0].account_id").value(TEACHER.toString()))
                .andExpect(jsonPath("$.can_manage").value(true))
                .andExpect(jsonPath("$.can_archive").value(false))
                .andExpect(jsonPath("$.student_count").value(0));
    }

    @Test
    void duplicateNameInSameSchoolReturns409() throws Exception {
        createCourseViaApi("M2 名称重复", MAJOR);

        createCourseViaApiExpecting("M2 名称重复", MAJOR, 409, "COURSE_NAME_TAKEN");
    }

    @Test
    void adminCannotCreateCourse() throws Exception {
        createCourseViaApiExpecting("M2 管理员建课", MAJOR, 403, "ROLE_NOT_ALLOWED", ADMIN);
    }

    @Test
    void majorOutsideTeacherScopeReturns422() throws Exception {
        createCourseViaApiExpecting("M2 越界专业", MAJOR2, 422, "COURSE_MAJOR_MISMATCH");
    }

    @Test
    void endAtInThePastReturns422() throws Exception {
        String body = """
                {"name": "M2 过去时间", "major_ids": ["%s"], "end_at": "2020-01-01T00:00:00Z"}
                """.formatted(MAJOR);

        mockMvc.perform(as(post("/api/v1/courses"), TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_END_AT"));
    }

    @Test
    void blankNameReturns400() throws Exception {
        mockMvc.perform(as(post("/api/v1/courses"), TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"  \", \"major_ids\": [\"" + MAJOR + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void crossSchoolReadReturns403() throws Exception {
        String courseId = createCourseViaApi("M2 跨校", MAJOR);

        mockMvc.perform(as(get("/api/v1/courses/{id}", courseId),
                        OTHER_SCHOOL_TEACHER, OTHER_SCHOOL, "TEACHER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SCHOOL_ACCESS"));
    }

    @Test
    void studentJoinsThenDuplicateJoinReturns409() throws Exception {
        String courseId = createCourseViaApi("M2 学生加入", MAJOR);

        mockMvc.perform(as(post("/api/v1/courses/{id}/join", courseId), STUDENT, SCHOOL, "STUDENT"))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(post("/api/v1/courses/{id}/join", courseId), STUDENT, SCHOOL, "STUDENT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_JOINED"));

        mockMvc.perform(as(get("/api/v1/courses/{id}", courseId), STUDENT, SCHOOL, "STUDENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joined").value(true))
                .andExpect(jsonPath("$.can_leave").value(true))
                .andExpect(jsonPath("$.student_count").value(1));
    }

    @Test
    void studentOutsideMajorCannotJoin() throws Exception {
        // 直接落库：教师专业范围只有 MAJOR，走 API 建 MAJOR2 课程会先在专业校验处 422。
        Course course = persistCourse("M2 专业不符", MAJOR2, FUTURE, TEACHER);

        mockMvc.perform(as(post("/api/v1/courses/{id}/join", course.id().value().toString()),
                        STUDENT, SCHOOL, "STUDENT"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COURSE_MAJOR_MISMATCH"));
    }

    @Test
    void joinAfterEndAtReturns422() throws Exception {
        Course course = persistCourse("M2 已结束", MAJOR, PAST, TEACHER);
        mockMvc.perform(as(post("/api/v1/courses/{id}/join", course.id().value().toString()),
                        STUDENT, SCHOOL, "STUDENT"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COURSE_ENDED"));
    }

    @Test
    void removingLastTeacherReturns422() throws Exception {
        String courseId = createCourseViaApi("M2 最后一名教师", MAJOR);

        mockMvc.perform(as(delete("/api/v1/courses/{id}/teachers/{accountId}", courseId, TEACHER),
                        TEACHER, SCHOOL, "TEACHER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LAST_TEACHER"));
    }

    @Test
    void addingTeacherFromOtherSchoolReturns403() throws Exception {
        String courseId = createCourseViaApi("M2 加教师", MAJOR);

        mockMvc.perform(as(post("/api/v1/courses/{id}/teachers", courseId), TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account_id\": \"" + OTHER_SCHOOL_TEACHER + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SCHOOL_ACCESS"));
    }

    @Test
    void teacherGroupIsFlatAndNewTeacherCanManage() throws Exception {
        String courseId = createCourseViaApi("M2 老师组平权", MAJOR);

        mockMvc.perform(as(post("/api/v1/courses/{id}/teachers", courseId), TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account_id\": \"" + OTHER_TEACHER + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(as(get("/api/v1/courses/{id}/teachers", courseId),
                        OTHER_TEACHER, SCHOOL, "TEACHER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(as(get("/api/v1/courses/{id}", courseId),
                        OTHER_TEACHER, SCHOOL, "TEACHER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.can_manage").value(true));

        mockMvc.perform(as(delete("/api/v1/courses/{id}/teachers/{accountId}", courseId, OTHER_TEACHER),
                        OTHER_TEACHER, SCHOOL, "TEACHER"))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/v1/courses/{id}/teachers", courseId),
                        TEACHER, SCHOOL, "TEACHER"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void studentCannotReadMemberList() throws Exception {
        String courseId = createCourseViaApi("M2 学生读名单", MAJOR);

        mockMvc.perform(as(get("/api/v1/courses/{id}/students", courseId), STUDENT, SCHOOL, "STUDENT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(as(get("/api/v1/courses/{id}/teachers", courseId), STUDENT, SCHOOL, "STUDENT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherOutsideGroupCannotManage() throws Exception {
        String courseId = createCourseViaApi("M2 非本组教师", MAJOR);

        mockMvc.perform(as(get("/api/v1/courses/{id}/students", courseId),
                        OUTSIDER_TEACHER, SCHOOL, "TEACHER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEACHER_NOT_IN_GROUP"));
    }

    @Test
    void invalidCourseIdReturns400() throws Exception {
        mockMvc.perform(as(get("/api/v1/courses/not-a-uuid"), TEACHER, SCHOOL, "TEACHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void nonUuidMajorIdReturns400InsteadOfInternalError() throws Exception {
        mockMvc.perform(as(post("/api/v1/courses"), TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"M2 非法专业\", \"major_ids\": [\"打打打\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("major_ids[0]"))
                .andExpect(jsonPath("$.trace_id").exists());
    }

    @Test
    void malformedEndAtReturns400InsteadOfInternalError() throws Exception {
        mockMvc.perform(as(post("/api/v1/courses"), TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"M2 非法日期\", \"major_ids\": [\"" + MAJOR
                                + "\"], \"end_at\": \"2030-01-01 00:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("end_at"));
    }

    @Test
    void nonUuidAccountIdReturns400InsteadOfInternalError() throws Exception {
        String courseId = createCourseViaApi("M2 非法账号", MAJOR);

        mockMvc.perform(as(post("/api/v1/courses/{id}/teachers", courseId),
                        TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account_id\": \"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void studentListOnlyShowsOwnMajorCourses() throws Exception {
        createCourseViaApi("M2 列表-本专业", MAJOR);
        persistCourse("M2 列表-其他专业", MAJOR2, FUTURE, TEACHER);

        mockMvc.perform(as(get("/api/v1/courses"), STUDENT, SCHOOL, "STUDENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("M2 列表-本专业"))
                .andExpect(jsonPath("$.items[0].joined").value(false))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void archiveHidesCourseFromStudentAndAdminCanRestore() throws Exception {
        String courseId = createCourseViaApi("M2 归档", MAJOR);

        mockMvc.perform(as(post("/api/v1/courses/{id}/archive", courseId), ADMIN, SCHOOL, "ADMIN"))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/v1/courses/{id}", courseId), STUDENT, SCHOOL, "STUDENT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(as(post("/api/v1/courses/{id}/join", courseId), STUDENT, SCHOOL, "STUDENT"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COURSE_ARCHIVED"));
        mockMvc.perform(as(post("/api/v1/courses/{id}/restore", courseId), ADMIN, SCHOOL, "ADMIN"))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/v1/courses/{id}", courseId), STUDENT, SCHOOL, "STUDENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived_at").value(nullValue()));
    }

    @Test
    void teacherArchiveIsForbidden() throws Exception {
        String courseId = createCourseViaApi("M2 教师归档", MAJOR);

        mockMvc.perform(as(post("/api/v1/courses/{id}/archive", courseId), TEACHER, SCHOOL, "TEACHER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void addStudentByClassMarksEnrollmentAndSkipsDuplicates() throws Exception {
        String courseId = createCourseViaApi("M2 按班级加人", MAJOR);

        mockMvc.perform(as(post("/api/v1/courses/{id}/students/class", courseId),
                        TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"class_id\": \"" + M2_CLASS + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.success").value(2))
                .andExpect(jsonPath("$.skipped").value(0));

        mockMvc.perform(as(post("/api/v1/courses/{id}/students/class", courseId),
                        TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"class_id\": \"" + M2_CLASS + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(0))
                .andExpect(jsonPath("$.skipped").value(2));

        mockMvc.perform(as(get("/api/v1/courses/{id}/students", courseId), TEACHER, SCHOOL, "TEACHER")
                        .param("include_history", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].join_method").value("class"))
                .andExpect(jsonPath("$.items[0].class_name").value("2024 M2 测试班级"));
    }

    @Test
    void studentsListRejectsInactiveClass() throws Exception {
        setClassActive(false);
        try {
            String courseId = createCourseViaApi("M2 停用班级", MAJOR);

            mockMvc.perform(as(post("/api/v1/courses/{id}/students/class", courseId),
                            TEACHER, SCHOOL, "TEACHER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"class_id\": \"" + M2_CLASS + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("CLASS_DISABLED"));
        } finally {
            // 班级状态被本用例改写，必须还原，否则后续 M2 / M3 用例拿到的夹具不一致。
            setClassActive(true);
        }
    }

    private void setClassActive(boolean active) {
        jdbc.update("UPDATE m1_auth.classes SET status = :status WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("status", active ? "active" : "inactive")
                        .addValue("id", M2_CLASS));
    }

    @Test
    void patchCourseRenamesAndKeepsContractFields() throws Exception {
        String courseId = createCourseViaApi("M2 改名前", MAJOR);

        mockMvc.perform(as(patch("/api/v1/courses/{id}", courseId), TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"M2 改名后\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("M2 改名后"))
                .andExpect(jsonPath("$.end_at").value("2030-01-01T00:00:00Z"));
    }

    private String createCourseViaApi(String name, UUID majorId) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/courses"), TEACHER, SCHOOL, "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courseJson(name, majorId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private void createCourseViaApiExpecting(String name, UUID majorId, int status, String code)
            throws Exception {
        createCourseViaApiExpecting(name, majorId, status, code, TEACHER);
    }

    private void createCourseViaApiExpecting(
            String name, UUID majorId, int status, String code, UUID accountId) throws Exception {
        mockMvc.perform(as(post("/api/v1/courses"), accountId, SCHOOL,
                        accountId.equals(ADMIN) ? "ADMIN" : "TEACHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courseJson(name, majorId)))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    private String courseJson(String name, UUID majorId) {
        return """
                {"name": "%s", "major_ids": ["%s"], "end_at": "2030-01-01T00:00:00Z"}
                """.formatted(name, majorId);
    }

    private Course persistCourse(String name, UUID majorId, Instant endAt, UUID creator) {
        Course course = Course.create(new CourseId(UUID.randomUUID()), new SchoolId(SCHOOL),
                new AccountId(creator), name, List.of(new MajorId(majorId)), endAt, Instant.now());
        courseRepository.save(course);
        return course;
    }

    private static MockHttpServletRequestBuilder as(
            MockHttpServletRequestBuilder builder, UUID accountId, UUID schoolId, String type) {
        return builder.header("X-Account-Id", accountId.toString())
                .header("X-School-Id", schoolId.toString())
                .header("X-Account-Type", type);
    }
}
