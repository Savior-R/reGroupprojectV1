package com.shijiangtai.m1auth.infrastructure.persistence;

import com.shijiangtai.m1auth.domain.AccountRoleRepository;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 账号角色仓储实现（V14 account_roles）。 */
@Repository
public class AccountRoleRepositoryAdapter implements AccountRoleRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AccountRoleRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<AccountType> findRoles(AccountId accountId) {
        Set<AccountType> roles = new HashSet<>();
        jdbc.query("""
                select role_code from m1_auth.account_roles where account_id = :id
                """, Map.of("id", accountId.value()), rs -> {
                    roles.add(AccountType.valueOf(
                            rs.getString("role_code").toUpperCase(Locale.ROOT)));
                });
        return Set.copyOf(roles);
    }

    @Override
    public void grant(AccountId accountId, AccountType role, AccountId grantedBy, Instant now) {
        jdbc.update("""
                insert into m1_auth.account_roles (account_id, role_code, granted_by, created_at)
                values (:accountId, :roleCode, :grantedBy, :createdAt)
                on conflict (account_id, role_code) do nothing
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId.value())
                .addValue("roleCode", role.name().toLowerCase(Locale.ROOT))
                .addValue("grantedBy", grantedBy == null ? null : grantedBy.value())
                .addValue("createdAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
    }

    @Override
    public void revoke(AccountId accountId, AccountType role) {
        jdbc.update("""
                delete from m1_auth.account_roles
                where account_id = :accountId and role_code = :roleCode
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId.value())
                .addValue("roleCode", role.name().toLowerCase(Locale.ROOT)));
    }
}
