package com.shijiangtai.m1auth.infrastructure.persistence;

import com.shijiangtai.m1auth.domain.SchoolAdminRepository;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 管理员-学校绑定（12 §3.1 school_admins）；MVP 一对一，靠部分唯一索引兜底（03 §3.4）。 */
@Repository
public class SchoolAdminRepositoryAdapter implements SchoolAdminRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SchoolAdminRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SchoolId> findActiveSchoolIdByAccount(AccountId accountId) {
        List<UUID> rows = jdbc.queryForList("""
                select school_id from m1_auth.school_admins
                where account_id = :accountId and status = 'active'
                """, Map.of("accountId", accountId.value()), UUID.class);
        return rows.stream().findFirst().map(SchoolId::new);
    }

    @Override
    public boolean existsActiveBySchool(SchoolId schoolId) {
        Boolean exists = jdbc.queryForObject("""
                select exists(select 1 from m1_auth.school_admins
                              where school_id = :schoolId and status = 'active')
                """, Map.of("schoolId", schoolId.value()), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void insert(SchoolId schoolId, AccountId accountId, boolean owner, Instant now) {
        jdbc.update("""
                insert into m1_auth.school_admins
                    (id, school_id, account_id, is_owner, status, joined_at, created_at, updated_at)
                values (:id, :schoolId, :accountId, :owner, 'active', :now, :now, :now)
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("schoolId", schoolId.value())
                .addValue("accountId", accountId.value())
                .addValue("owner", owner)
                .addValue("now", AccountRepositoryAdapter.toOffset(now)));
    }

    @Override
    public Optional<AccountId> findActiveAccountBySchool(SchoolId schoolId) {
        List<UUID> rows = jdbc.queryForList("""
                select account_id from m1_auth.school_admins
                where school_id = :schoolId and status = 'active'
                """, Map.of("schoolId", schoolId.value()), UUID.class);
        return rows.stream().findFirst().map(AccountId::new);
    }
}
