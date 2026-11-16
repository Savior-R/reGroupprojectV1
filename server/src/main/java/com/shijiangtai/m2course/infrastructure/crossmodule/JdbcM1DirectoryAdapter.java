package com.shijiangtai.m2course.infrastructure.crossmodule;

import com.shijiangtai.m2course.application.port.M1DirectoryPort;
import com.shijiangtai.m2course.domain.MajorId;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * M1 组织数据只读适配器（m1_auth schema）。
 *
 * <p>只 select、不写；M1 模块落地后，本适配器可替换为调用 {@code m1auth} 的 application 查询接口，
 * 端口签名保持不变。SQL 里对 {@code m1_auth.accounts.username} 的引用也仅用于 M2 列表按教师姓名检索。
 */
@Repository
public class JdbcM1DirectoryAdapter implements M1DirectoryPort {
    private static final String SELECT_ACCOUNT = """
            SELECT a.id, a.username, a.school_id, t.code AS type_code
              FROM m1_auth.accounts a
              JOIN m1_auth.account_types t ON t.id = a.account_type_id
            """;

    private static final String SELECT_STUDENT = """
            SELECT a.id, a.username, a.school_id, sp.class_id, c.major_id
              FROM m1_auth.student_profiles sp
              JOIN m1_auth.accounts a ON a.id = sp.account_id
              JOIN m1_auth.classes c ON c.id = sp.class_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcM1DirectoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccountSnapshot> findAccount(AccountId accountId) {
        return jdbc.query(SELECT_ACCOUNT + " WHERE a.id = :id",
                        new MapSqlParameterSource("id", accountId.value()),
                        (rs, index) -> account(rs))
                .stream().findFirst();
    }

    @Override
    public List<AccountSnapshot> findAccounts(Collection<AccountId> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = accountIds.stream().map(AccountId::value).toList();
        return jdbc.query(SELECT_ACCOUNT + " WHERE a.id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                (rs, index) -> account(rs));
    }

    @Override
    public Optional<MajorSnapshot> findMajor(MajorId majorId) {
        return findMajors(List.of(majorId)).stream().findFirst();
    }

    @Override
    public List<MajorSnapshot> findMajors(Collection<MajorId> majorIds) {
        if (majorIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = majorIds.stream().map(MajorId::value).toList();
        return jdbc.query("""
                        SELECT id, school_id, name, status FROM m1_auth.majors WHERE id IN (:ids)
                        """,
                new MapSqlParameterSource("ids", ids),
                (rs, index) -> new MajorSnapshot(
                        new MajorId(rs.getObject("id", UUID.class)),
                        new SchoolId(rs.getObject("school_id", UUID.class)),
                        rs.getString("name"),
                        isActive(rs.getString("status"))));
    }

    @Override
    public List<MajorId> activeMajorIds(AccountId teacherId) {
        return jdbc.query("""
                        SELECT tm.major_id
                          FROM m1_auth.teacher_majors tm
                          JOIN m1_auth.majors m ON m.id = tm.major_id
                         WHERE tm.account_id = :accountId
                           AND tm.status = 'active'
                           AND (tm.effective_to IS NULL OR tm.effective_to > now())
                           AND m.status = 'active'
                        """,
                new MapSqlParameterSource("accountId", teacherId.value()),
                (rs, index) -> new MajorId(rs.getObject("major_id", UUID.class)));
    }

    @Override
    public Optional<ClassSnapshot> findClass(ClassId classId) {
        return jdbc.query("""
                        SELECT id, school_id, major_id, grade, name, status
                          FROM m1_auth.classes WHERE id = :id
                        """,
                new MapSqlParameterSource("id", classId.value()),
                (rs, index) -> new ClassSnapshot(
                        new ClassId(rs.getObject("id", UUID.class)),
                        new SchoolId(rs.getObject("school_id", UUID.class)),
                        new MajorId(rs.getObject("major_id", UUID.class)),
                        rs.getString("grade"),
                        rs.getString("name"),
                        isActive(rs.getString("status"))))
                .stream().findFirst();
    }

    @Override
    public List<StudentSnapshot> findStudentsByClass(ClassId classId) {
        return jdbc.query(SELECT_STUDENT + " WHERE sp.class_id = :classId ORDER BY a.username",
                new MapSqlParameterSource("classId", classId.value()),
                (rs, index) -> student(rs));
    }

    @Override
    public Optional<StudentSnapshot> findStudent(AccountId accountId) {
        return jdbc.query(SELECT_STUDENT + " WHERE sp.account_id = :id",
                        new MapSqlParameterSource("id", accountId.value()),
                        (rs, index) -> student(rs))
                .stream().findFirst();
    }

    @Override
    public List<StudentSnapshot> findStudents(Collection<AccountId> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = accountIds.stream().map(AccountId::value).toList();
        return jdbc.query(SELECT_STUDENT + " WHERE sp.account_id IN (:ids)",
                new MapSqlParameterSource("ids", new LinkedHashSet<>(ids)),
                (rs, index) -> student(rs));
    }

    private AccountSnapshot account(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID schoolId = rs.getObject("school_id", UUID.class);
        return new AccountSnapshot(
                new AccountId(rs.getObject("id", UUID.class)),
                rs.getString("username"),
                schoolId == null ? null : new SchoolId(schoolId),
                AccountType.valueOf(rs.getString("type_code").toUpperCase(Locale.ROOT)));
    }

    private StudentSnapshot student(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StudentSnapshot(
                new AccountId(rs.getObject("id", UUID.class)),
                rs.getString("username"),
                new SchoolId(rs.getObject("school_id", UUID.class)),
                new ClassId(rs.getObject("class_id", UUID.class)),
                new MajorId(rs.getObject("major_id", UUID.class)));
    }

    private boolean isActive(String status) {
        return "active".equalsIgnoreCase(status);
    }
}
