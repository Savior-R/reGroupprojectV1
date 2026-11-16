package com.shijiangtai.m2course.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m2course.api.CourseController;
import com.shijiangtai.m2course.api.CourseMemberController;
import com.shijiangtai.m2course.domain.CourseStatus;
import com.shijiangtai.m2course.domain.JoinMethod;
import com.shijiangtai.m2course.domain.StudentStatus;
import com.shijiangtai.m2course.domain.TeacherStatus;
import com.shijiangtai.shared.domain.ErrorCode;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * M2 契约测试（不连数据库）：错误码、状态枚举与表约束、HTTP 端点三处必须与文档一致。
 *
 * <p>这三处是最容易在重构中悄悄漂移的地方：错误码改了前端就解析不了，枚举值与 DB CHECK
 * 不一致会直接写库失败，端点路径变了会打穿 `02` 的 API 契约。断言写死的是文档值，
 * 不是实现值，所以它守的是契约而不是代码。
 */
class M2ContractTest {

    /** docs/design/02 §8.2.1 里与 M2 相关的错误码及其 HTTP 状态。 */
    private static final Map<String, Integer> M2_ERROR_CODES = new LinkedHashMap<>();

    static {
        M2_ERROR_CODES.put("VALIDATION_ERROR", 400);
        M2_ERROR_CODES.put("UNAUTHORIZED", 401);
        M2_ERROR_CODES.put("FORBIDDEN", 403);
        M2_ERROR_CODES.put("CROSS_SCHOOL_ACCESS", 403);
        M2_ERROR_CODES.put("ROLE_NOT_ALLOWED", 403);
        M2_ERROR_CODES.put("TEACHER_NOT_IN_GROUP", 403);
        M2_ERROR_CODES.put("RESOURCE_NOT_FOUND", 404);
        M2_ERROR_CODES.put("DUPLICATE_RESOURCE", 409);
        M2_ERROR_CODES.put("COURSE_NAME_TAKEN", 409);
        M2_ERROR_CODES.put("ALREADY_JOINED", 409);
        M2_ERROR_CODES.put("COURSE_ARCHIVED", 422);
        M2_ERROR_CODES.put("COURSE_ENDED", 422);
        M2_ERROR_CODES.put("COURSE_MAJOR_MISMATCH", 422);
        M2_ERROR_CODES.put("MAJOR_DISABLED", 422);
        M2_ERROR_CODES.put("CLASS_DISABLED", 422);
        M2_ERROR_CODES.put("CLASS_NOT_ELIGIBLE", 422);
        M2_ERROR_CODES.put("LAST_TEACHER", 422);
        M2_ERROR_CODES.put("INVALID_END_AT", 422);
    }

    @Test
    void errorCodesMatchFrozenContract() {
        M2_ERROR_CODES.forEach((code, expectedStatus) -> {
            ErrorCode actual = ErrorCode.valueOf(code);
            assertThat(actual.httpStatus())
                    .as("02 §8.2.1 规定 %s 的 HTTP 状态为 %d", code, expectedStatus)
                    .isEqualTo(expectedStatus);
        });
    }

    @Test
    void courseStatusMatchesTableConstraint() throws IOException {
        assertThat(valuesOf("ck_courses_status"))
                .isEqualTo(lowerNames(CourseStatus.values()));
    }

    @Test
    void teacherStatusMatchesTableConstraint() throws IOException {
        assertThat(valuesOf("ck_course_teachers_status"))
                .isEqualTo(lowerNames(TeacherStatus.values()));
    }

    @Test
    void studentStatusMatchesTableConstraint() throws IOException {
        assertThat(valuesOf("ck_course_students_status"))
                .isEqualTo(lowerNames(StudentStatus.values()));
    }

    @Test
    void joinMethodMatchesTableConstraint() throws IOException {
        assertThat(valuesOf("ck_course_students_method"))
                .isEqualTo(lowerNames(JoinMethod.values()));
    }

    @Test
    void courseEndpointsMatchContract() {
        assertThat(endpointsOf(CourseController.class)).isEqualTo(Map.of(
                "/api/v1/courses", Set.of("GET", "POST"),
                "/api/v1/courses/{courseId}", Set.of("GET", "PATCH"),
                "/api/v1/courses/{courseId}/archive", Set.of("POST"),
                "/api/v1/courses/{courseId}/restore", Set.of("POST")));
    }

    @Test
    void memberEndpointsMatchContract() {
        assertThat(endpointsOf(CourseMemberController.class)).isEqualTo(Map.of(
                "/api/v1/courses/{courseId}/teachers", Set.of("GET", "POST"),
                "/api/v1/courses/{courseId}/teachers/{accountId}", Set.of("DELETE"),
                "/api/v1/courses/{courseId}/students", Set.of("GET"),
                "/api/v1/courses/{courseId}/students/class", Set.of("POST"),
                "/api/v1/courses/{courseId}/students/account", Set.of("POST"),
                "/api/v1/courses/{courseId}/students/{accountId}", Set.of("DELETE"),
                "/api/v1/courses/{courseId}/join", Set.of("POST"),
                "/api/v1/courses/{courseId}/leave", Set.of("POST")));
    }

    private static Set<String> lowerNames(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(value -> value.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> valuesOf(String constraintName) throws IOException {
        String sql = new ClassPathResource("db/migration/V2__m2_course.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                        constraintName + "\\s+CHECK\\s*\\(\\s*\\w+\\s+IN\\s*\\(([^)]*)\\)")
                .matcher(sql);
        assertThat(matcher.find()).as("V2 迁移里应存在约束 %s", constraintName).isTrue();
        return Arrays.stream(matcher.group(1).split(","))
                .map(String::trim)
                .map(value -> value.replace("'", ""))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, Set<String>> endpointsOf(Class<?> controller) {
        String base = String.join("", controller.getAnnotation(RequestMapping.class).value());
        Map<String, Set<String>> endpoints = new TreeMap<>();
        for (Method method : controller.getDeclaredMethods()) {
            for (Annotation annotation : method.getAnnotations()) {
                String httpMethod = httpMethodOf(annotation);
                if (httpMethod == null) {
                    continue;
                }
                String path = base + String.join("", pathOf(annotation));
                endpoints.computeIfAbsent(path, key -> new TreeSet<>()).add(httpMethod);
            }
        }
        return endpoints;
    }

    private static String httpMethodOf(Annotation annotation) {
        if (annotation instanceof GetMapping) {
            return "GET";
        }
        if (annotation instanceof PostMapping) {
            return "POST";
        }
        if (annotation instanceof PatchMapping) {
            return "PATCH";
        }
        if (annotation instanceof DeleteMapping) {
            return "DELETE";
        }
        return null;
    }

    private static String[] pathOf(Annotation annotation) {
        if (annotation instanceof GetMapping mapping) {
            return mapping.value();
        }
        if (annotation instanceof PostMapping mapping) {
            return mapping.value();
        }
        if (annotation instanceof PatchMapping mapping) {
            return mapping.value();
        }
        if (annotation instanceof DeleteMapping mapping) {
            return mapping.value();
        }
        return new String[] {""};
    }
}
