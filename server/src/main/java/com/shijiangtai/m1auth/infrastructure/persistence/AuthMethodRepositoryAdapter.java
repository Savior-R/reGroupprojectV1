package com.shijiangtai.m1auth.infrastructure.persistence;

import com.shijiangtai.m1auth.domain.AuthLoginEvent;
import com.shijiangtai.m1auth.domain.AuthMethodRepository;
import com.shijiangtai.m1auth.domain.EmailLoginCode;
import com.shijiangtai.m1auth.domain.OAuthIdentity;
import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 微信身份、邮箱验证码、登录审计（12 §3.1 三张新表）。 */
@Repository
public class AuthMethodRepositoryAdapter implements AuthMethodRepository {

    private static final RowMapper<OAuthIdentity> IDENTITY_MAPPER = (rs, i) -> new OAuthIdentity(
            rs.getObject("id", UUID.class),
            new AccountId(rs.getObject("account_id", UUID.class)),
            rs.getString("provider"),
            rs.getString("provider_user_id"),
            rs.getString("union_id"),
            rs.getString("email"),
            rs.getString("nickname"),
            rs.getString("avatar_url"),
            AccountRepositoryAdapter.toInstant(
                    rs.getObject("created_at", OffsetDateTime.class)),
            AccountRepositoryAdapter.toInstant(
                    rs.getObject("last_login_at", OffsetDateTime.class)));

    private static final RowMapper<EmailLoginCode> CODE_MAPPER = (rs, i) -> new EmailLoginCode(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("code_hash"),
            EmailLoginCode.Purpose.valueOf(
                    rs.getString("purpose").toUpperCase(java.util.Locale.ROOT)),
            AccountRepositoryAdapter.toInstant(rs.getObject("expires_at", OffsetDateTime.class)),
            rs.getInt("attempts"),
            AccountRepositoryAdapter.toInstant(rs.getObject("consumed_at", OffsetDateTime.class)),
            rs.getString("request_ip"),
            AccountRepositoryAdapter.toInstant(rs.getObject("created_at", OffsetDateTime.class)));

    private static final RowMapper<AuthLoginEvent> EVENT_MAPPER = (rs, i) -> new AuthLoginEvent(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class) == null
                    ? null : new AccountId(rs.getObject("account_id", UUID.class)),
            AuthLoginEvent.LoginMethod.valueOf(
                    rs.getString("method").toUpperCase(java.util.Locale.ROOT)),
            rs.getString("provider"),
            rs.getBoolean("success"),
            rs.getString("ip"),
            rs.getString("user_agent"),
            AccountRepositoryAdapter.toInstant(rs.getObject("created_at", OffsetDateTime.class)));

    private final NamedParameterJdbcTemplate jdbc;

    public AuthMethodRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------ oauth_identities

    @Override
    public Optional<OAuthIdentity> findIdentity(String provider, String providerUserId) {
        return jdbc.query("""
                select * from m1_auth.oauth_identities
                where provider = :provider and provider_user_id = :providerUserId
                """, Map.of("provider", provider, "providerUserId", providerUserId), IDENTITY_MAPPER)
                .stream().findFirst();
    }

    @Override
    public Optional<OAuthIdentity> findIdentityByAccount(AccountId accountId, String provider) {
        return jdbc.query("""
                select * from m1_auth.oauth_identities
                where account_id = :accountId and provider = :provider
                """, Map.of("accountId", accountId.value(), "provider", provider), IDENTITY_MAPPER)
                .stream().findFirst();
    }

    @Override
    public OAuthIdentity saveIdentity(OAuthIdentity identity) {
        jdbc.update("""
                insert into m1_auth.oauth_identities
                    (id, account_id, provider, provider_user_id, union_id, email, nickname,
                     avatar_url, created_at, last_login_at)
                values (:id, :accountId, :provider, :providerUserId, :unionId, :email, :nickname,
                        :avatarUrl, :createdAt, :lastLoginAt)
                on conflict (id) do update set
                    union_id = excluded.union_id, email = excluded.email,
                    nickname = excluded.nickname, avatar_url = excluded.avatar_url,
                    last_login_at = excluded.last_login_at
                """, new MapSqlParameterSource()
                .addValue("id", identity.id())
                .addValue("accountId", identity.accountId().value())
                .addValue("provider", identity.provider())
                .addValue("providerUserId", identity.providerUserId())
                .addValue("unionId", identity.unionId())
                .addValue("email", identity.email())
                .addValue("nickname", identity.nickname())
                .addValue("avatarUrl", identity.avatarUrl())
                .addValue("createdAt", AccountRepositoryAdapter.toOffset(identity.createdAt()))
                .addValue("lastLoginAt", AccountRepositoryAdapter.toOffset(identity.lastLoginAt())));
        return identity;
    }

    @Override
    public void deleteIdentity(AccountId accountId, String provider) {
        jdbc.update("""
                delete from m1_auth.oauth_identities
                where account_id = :accountId and provider = :provider
                """, Map.of("accountId", accountId.value(), "provider", provider));
    }

    // --------------------------------------------------- email_login_codes

    @Override
    public Optional<EmailLoginCode> findLatestCode(String email, EmailLoginCode.Purpose purpose) {
        return jdbc.query("""
                select * from m1_auth.email_login_codes
                where email = :email and purpose = :purpose
                order by created_at desc
                limit 1
                """, Map.of("email", email, "purpose", purpose.dbValue()), CODE_MAPPER)
                .stream().findFirst();
    }

    @Override
    public Optional<Instant> findLastCodeCreatedAt(String email) {
        List<Instant> rows = jdbc.query("""
                select created_at from m1_auth.email_login_codes
                where email = :email order by created_at desc limit 1
                """, Map.of("email", email),
                (rs, i) -> AccountRepositoryAdapter.toInstant(
                        rs.getObject("created_at", OffsetDateTime.class)));
        return rows.stream().findFirst();
    }

    @Override
    public long countCodesByEmailSince(String email, Instant since) {
        Long count = jdbc.queryForObject("""
                select count(*) from m1_auth.email_login_codes
                where email = :email and created_at >= :since
                """, Map.of("email", email, "since", AccountRepositoryAdapter.toOffset(since)),
                Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public long countCodesByIpSince(String ip, Instant since) {
        if (ip == null || ip.isBlank()) {
            return 0L;
        }
        Long count = jdbc.queryForObject("""
                select count(*) from m1_auth.email_login_codes
                where request_ip = cast(:ip as inet) and created_at >= :since
                """, Map.of("ip", ip, "since", AccountRepositoryAdapter.toOffset(since)),
                Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public EmailLoginCode insertCode(EmailLoginCode code) {
        jdbc.update("""
                insert into m1_auth.email_login_codes
                    (id, email, code_hash, purpose, expires_at, attempts, consumed_at,
                     request_ip, created_at)
                values (:id, :email, :codeHash, :purpose, :expiresAt, :attempts, :consumedAt,
                        cast(:requestIp as inet), :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", code.id())
                .addValue("email", code.email())
                .addValue("codeHash", code.codeHash())
                .addValue("purpose", code.purpose().dbValue())
                .addValue("expiresAt", AccountRepositoryAdapter.toOffset(code.expiresAt()))
                .addValue("attempts", code.attempts())
                .addValue("consumedAt", AccountRepositoryAdapter.toOffset(code.consumedAt()))
                .addValue("requestIp", code.requestIp())
                .addValue("createdAt", AccountRepositoryAdapter.toOffset(code.createdAt())));
        return code;
    }

    @Override
    public void updateCode(EmailLoginCode code) {
        jdbc.update("""
                update m1_auth.email_login_codes
                set attempts = :attempts, consumed_at = :consumedAt
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", code.id())
                .addValue("attempts", code.attempts())
                .addValue("consumedAt", AccountRepositoryAdapter.toOffset(code.consumedAt())));
    }

    @Override
    public void invalidatePendingCodes(String email, Instant now) {
        jdbc.update("""
                update m1_auth.email_login_codes
                set consumed_at = :now
                where email = :email and consumed_at is null
                """, new MapSqlParameterSource()
                .addValue("email", email)
                .addValue("now", AccountRepositoryAdapter.toOffset(now)));
    }

    // --------------------------------------------------- auth_login_events

    @Override
    public void insertLoginEvent(AuthLoginEvent event) {
        jdbc.update("""
                insert into m1_auth.auth_login_events
                    (id, account_id, method, provider, success, ip, user_agent, created_at)
                values (:id, :accountId, :method, :provider, :success,
                        cast(:ip as inet), :userAgent, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", event.id())
                .addValue("accountId",
                        event.accountId() == null ? null : event.accountId().value())
                .addValue("method", event.method().dbValue())
                .addValue("provider", event.provider())
                .addValue("success", event.success())
                .addValue("ip", event.ip())
                .addValue("userAgent", event.userAgent())
                .addValue("createdAt", AccountRepositoryAdapter.toOffset(event.createdAt())));
    }

    @Override
    public List<AuthLoginEvent> findRecentEvents(UUID accountId, int limit) {
        return jdbc.query("""
                select * from m1_auth.auth_login_events
                where account_id = :accountId
                order by created_at desc limit :limit
                """, Map.of("accountId", accountId, "limit", limit), EVENT_MAPPER);
    }
}
