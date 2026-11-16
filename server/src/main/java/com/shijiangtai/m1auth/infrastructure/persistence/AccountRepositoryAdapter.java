package com.shijiangtai.m1auth.infrastructure.persistence;

import com.shijiangtai.m1auth.domain.Account;
import com.shijiangtai.m1auth.domain.AccountRepository;
import com.shijiangtai.m1auth.domain.EmailAddress;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 账号仓储实现（12 §3.1 accounts）。账号类型经 account_types.code 解析。 */
@Repository
public class AccountRepositoryAdapter implements AccountRepository {

    private static final String SELECT = """
            select a.id, a.username, a.password_hash, a.email, a.email_verified_at,
                   t.code as account_type_code, a.school_id, a.created_at, a.updated_at
            from m1_auth.accounts a
            join m1_auth.account_types t on t.id = a.account_type_id
            """;

    private static final RowMapper<Account> MAPPER = AccountRepositoryAdapter::map;

    private final NamedParameterJdbcTemplate jdbc;

    public AccountRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        return jdbc.query(SELECT + " where a.id = :id", Map.of("id", id.value()), MAPPER)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        return jdbc.query(SELECT + " where a.username = :username", Map.of("username", username), MAPPER)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Account> findByEmail(EmailAddress email) {
        return jdbc.query(
                        SELECT + " where a.email = :email",
                        Map.of("email", email.value()),
                        MAPPER)
                .stream()
                .findFirst();
    }

    @Override
    public boolean existsByUsername(String username) {
        Boolean exists = jdbc.queryForObject(
                "select exists(select 1 from m1_auth.accounts where username = :username)",
                Map.of("username", username),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public boolean existsByEmail(EmailAddress email) {
        Boolean exists = jdbc.queryForObject(
                "select exists(select 1 from m1_auth.accounts where email = :email)",
                Map.of("email", email.value()),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Account save(Account account) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", account.id().value())
                .addValue("username", account.username())
                .addValue("passwordHash", account.passwordHash())
                .addValue("email", account.email() == null ? null : account.email().value())
                .addValue("emailVerifiedAt", toOffset(account.emailVerifiedAt()))
                .addValue("typeCode", account.type().name().toLowerCase(java.util.Locale.ROOT))
                .addValue("schoolId",
                        account.schoolId() == null ? null : account.schoolId().value())
                .addValue("createdAt", toOffset(account.createdAt()))
                .addValue("updatedAt", toOffset(account.updatedAt()));

        jdbc.update("""
                insert into m1_auth.accounts
                    (id, username, password_hash, email, email_verified_at,
                     account_type_id, school_id, created_at, updated_at)
                values
                    (:id, :username, :passwordHash, :email, :emailVerifiedAt,
                     (select id from m1_auth.account_types where code = :typeCode),
                     :schoolId, :createdAt, :updatedAt)
                on conflict (id) do update set
                    username = excluded.username,
                    password_hash = excluded.password_hash,
                    email = excluded.email,
                    email_verified_at = excluded.email_verified_at,
                    school_id = excluded.school_id,
                    updated_at = excluded.updated_at
                """, params);

        // 主身份角色随账号落库：所有建号路径（注册 / OAuth 引导注册）都经过这里，
        // 放在仓储层可保证「账号存在 ⇒ 至少有一个角色」，不依赖各服务各自记得写。
        // on conflict do nothing：不会覆盖已授予的附加角色（如 admin）。
        jdbc.update("""
                insert into m1_auth.account_roles (account_id, role_code, granted_by, created_at)
                values (:id, :typeCode, null, :createdAt)
                on conflict (account_id, role_code) do nothing
                """, params);
        return account;
    }

    private static Account map(ResultSet rs, int rowNum) throws SQLException {
        String email = rs.getString("email");
        UUID schoolId = rs.getObject("school_id", UUID.class);
        return new Account(
                new AccountId(rs.getObject("id", UUID.class)),
                rs.getString("username"),
                rs.getString("password_hash"),
                email == null ? null : EmailAddress.of(email),
                toInstant(rs.getObject("email_verified_at", OffsetDateTime.class)),
                AccountType.valueOf(
                        rs.getString("account_type_code").toUpperCase(java.util.Locale.ROOT)),
                schoolId == null ? null : new SchoolId(schoolId),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
    }

    static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
