package com.shijiangtai.m2course.persistence;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * M2 集成测试基类：真实 PostgreSQL + Flyway 迁移 + M1 组织夹具。
 *
 * <p>{@code /db/test-upstream.sql} 提供学校 / 账号 / 专业 / 班级 / 一门基础课程（M3 共用）；
 * 本类在此之上补 M2 需要的第二个学校、管理员、外校教师、学生档案与教师专业范围，
 * 并在每条用例后清理自己写进 {@code m2_course} 的行（不删除基础课程，避免破坏 M3 夹具）。
 *
 * <p>夹具全部用 {@code ON CONFLICT} / {@code NOT EXISTS} 写成幂等语句，
 * 因此 M3 与本类的用例混跑时不会互相污染。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/db/test-upstream.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class M2PostgresIT {
    protected static final UUID SCHOOL = uuid("00000000-0000-0000-0000-000000000001");
    protected static final UUID OTHER_SCHOOL = uuid("00000000-0000-0000-0000-000000000002");
    protected static final UUID TEACHER = uuid("00000000-0000-0000-0000-000000000010");
    protected static final UUID OTHER_TEACHER = uuid("00000000-0000-0000-0000-000000000011");
    protected static final UUID OUTSIDER_TEACHER = uuid("00000000-0000-0000-0000-000000000012");
    protected static final UUID STUDENT = uuid("00000000-0000-0000-0000-000000000020");
    protected static final UUID STUDENT2 = uuid("00000000-0000-0000-0000-000000000021");
    protected static final UUID ADMIN = uuid("00000000-0000-0000-0000-000000000030");
    protected static final UUID OTHER_SCHOOL_TEACHER = uuid("00000000-0000-0000-0000-000000000040");
    protected static final UUID MAJOR = uuid("00000000-0000-0000-0000-000000000300");
    protected static final UUID MAJOR2 = uuid("00000000-0000-0000-0000-000000000301");
    protected static final UUID OTHER_MAJOR = uuid("00000000-0000-0000-0000-000000000310");
    protected static final UUID CLASS = uuid("00000000-0000-0000-0000-000000000200");
    /** test-upstream.sql 里的基础课程，M3 用例依赖它，M2 不删也不改。 */
    protected static final UUID BASE_COURSE = uuid("00000000-0000-0000-0000-000000000100");

    @Autowired
    protected NamedParameterJdbcTemplate jdbc;

    protected static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    @BeforeEach
    void seedM2Fixtures() {
        insertSchool(OTHER_SCHOOL, "外校");
        insertAccount(ADMIN, "m2.admin", SCHOOL, "admin");
        insertAccount(OUTSIDER_TEACHER, "m2.outsider.teacher", SCHOOL, "teacher");
        insertAccount(OTHER_SCHOOL_TEACHER, "m2.other.school.teacher", OTHER_SCHOOL, "teacher");
        insertMajor(MAJOR2, SCHOOL, "第二专业");
        insertMajor(OTHER_MAJOR, OTHER_SCHOOL, "外校专业");
        insertTeacherMajor(TEACHER, MAJOR);
        insertTeacherMajor(OTHER_TEACHER, MAJOR);
        insertTeacherMajor(OUTSIDER_TEACHER, MAJOR);
        insertTeacherMajor(OTHER_SCHOOL_TEACHER, OTHER_MAJOR);
        insertStudentProfile(STUDENT, CLASS);
        insertStudentProfile(STUDENT2, CLASS);
    }

    @AfterEach
    void cleanM2Rows() {
        jdbc.update("DELETE FROM m2_course.course_students", new MapSqlParameterSource());
        jdbc.update("DELETE FROM m2_course.course_teachers", new MapSqlParameterSource());
        jdbc.update("DELETE FROM m2_course.course_majors", new MapSqlParameterSource());
        jdbc.update("DELETE FROM m2_course.courses WHERE id <> :base",
                new MapSqlParameterSource("base", BASE_COURSE));
    }

    protected void insertSchool(UUID id, String name) {
        jdbc.update("""
                INSERT INTO m1_auth.schools (id, name) VALUES (:id, :name)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource().addValue("id", id).addValue("name", name));
    }

    protected void insertAccount(UUID id, String username, UUID schoolId, String typeCode) {
        jdbc.update("""
                INSERT INTO m1_auth.accounts (id, username, password_hash, account_type_id, school_id)
                SELECT :id, :username, 'x', t.id, :schoolId
                  FROM m1_auth.account_types t
                 WHERE t.code = :typeCode
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("username", username)
                .addValue("schoolId", schoolId)
                .addValue("typeCode", typeCode));
    }

    protected void insertMajor(UUID id, UUID schoolId, String name) {
        jdbc.update("""
                INSERT INTO m1_auth.majors (id, school_id, name) VALUES (:id, :schoolId, :name)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("schoolId", schoolId)
                .addValue("name", name));
    }

    protected void insertTeacherMajor(UUID accountId, UUID majorId) {
        jdbc.update("""
                INSERT INTO m1_auth.teacher_majors (account_id, major_id)
                SELECT :accountId, :majorId
                 WHERE NOT EXISTS (SELECT 1 FROM m1_auth.teacher_majors
                                    WHERE account_id = :accountId AND major_id = :majorId)
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("majorId", majorId));
    }

    protected void insertStudentProfile(UUID accountId, UUID classId) {
        jdbc.update("""
                INSERT INTO m1_auth.student_profiles (account_id, class_id) VALUES (:accountId, :classId)
                ON CONFLICT (account_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("classId", classId));
    }
}
