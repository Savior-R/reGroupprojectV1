package com.shijiangtai.m1auth.application;

import com.shijiangtai.m1auth.application.port.OAuthProvider;
import com.shijiangtai.m1auth.application.port.OAuthStateStore;
import com.shijiangtai.m1auth.application.port.OAuthStateStore.TicketPayload;
import com.shijiangtai.m1auth.application.port.PasswordHasher;
import com.shijiangtai.m1auth.domain.Account;
import com.shijiangtai.m1auth.domain.AccountRepository;
import com.shijiangtai.m1auth.domain.AuthLoginEvent;
import com.shijiangtai.m1auth.domain.AuthMethodRepository;
import com.shijiangtai.m1auth.domain.OAuthIdentity;
import com.shijiangtai.m1auth.infrastructure.config.M1Properties;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 微信开放平台 PC 扫码登录与绑定（FR-M1-10、FR-M1-11）。
 *
 * <p>state / ticket 均一次性、5 分钟；provider access token 不落库、不返回前端；
 * 未绑定身份不自动建号，一律走引导注册或登录后绑定。
 */
@Service
public class OAuthLoginService {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginService.class);

    private final OAuthProvider provider;
    private final OAuthStateStore stateStore;
    private final AuthMethodRepository authMethods;
    private final AccountRepository accounts;
    private final PasswordHasher passwordHasher;
    private final AuthApplicationService authService;
    private final LoginAuditWriter auditWriter;
    private final M1Properties properties;
    private final Clock clock;

    public OAuthLoginService(
            OAuthProvider provider,
            OAuthStateStore stateStore,
            AuthMethodRepository authMethods,
            AccountRepository accounts,
            PasswordHasher passwordHasher,
            AuthApplicationService authService,
            LoginAuditWriter auditWriter,
            M1Properties properties,
            Clock clock) {
        this.provider = provider;
        this.stateStore = stateStore;
        this.authMethods = authMethods;
        this.accounts = accounts;
        this.passwordHasher = passwordHasher;
        this.authService = authService;
        this.auditWriter = auditWriter;
        this.properties = properties;
        this.clock = clock;
    }

    /** 生成一次性 state 与授权 URL（02 §4.1.1）。 */
    public QrView createQr() {
        String state = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(properties.oauth().stateTtl());
        stateStore.create(state, properties.oauth().stateTtl());
        return new QrView(state, provider.authorizeUrl(state), expiresAt);
    }

    /**
     * 微信回调：校验 state → 换用户信息 → 已绑定发 login_ticket，未绑定发 registration_ticket。
     * 不返回 token（FR-M1-10）。
     */
    public void handleCallback(String code, String state) {
        if (stateStore.status(state).isEmpty()) {
            throw new DomainException(ErrorCode.OAUTH_STATE_INVALID, "二维码已过期，请刷新重试");
        }

        OAuthProvider.OAuthUserInfo info;
        try {
            info = provider.exchange(code);
        } catch (OAuthProvider.OAuthUnavailableException e) {
            stateStore.markFailed(state);
            throw new DomainException(
                    ErrorCode.OAUTH_PROVIDER_UNAVAILABLE, "微信服务暂不可用，请稍后重试");
        } catch (OAuthProvider.OAuthRejectedException e) {
            stateStore.markFailed(state);
            throw new DomainException(ErrorCode.OAUTH_STATE_INVALID, "微信授权失败，请重新扫码");
        }

        TicketPayload payload = new TicketPayload(
                info.provider(), info.providerUserId(), info.unionId(),
                info.email(), info.nickname(), info.avatarUrl());
        String ticket = UUID.randomUUID().toString();

        boolean bound = authMethods
                .findIdentity(info.provider(), info.providerUserId())
                .isPresent();
        if (bound) {
            stateStore.markAuthorized(state, ticket, payload, properties.oauth().ticketTtl());
        } else {
            stateStore.markNeedsRegistration(
                    state, ticket, payload, properties.oauth().ticketTtl());
        }
    }

    /** 前端轮询；未发起或已过期都表现为 {@code expired}（02 §4.1.1）。 */
    public StatusView status(String state) {
        return stateStore.status(state)
                .map(thread -> new StatusView(thread.status().wireValue(), thread.ticket()))
                .orElseGet(() -> new StatusView("expired", null));
    }

    /** login_ticket 换 JWT；ticket 一次性。 */
    @Transactional
    public AuthApplicationService.AuthResult exchange(
            String ticket, String requestIp, String userAgent) {
        Optional<TicketPayload> found = stateStore.takeLoginTicket(ticket);
        if (found.isEmpty()) {
            // 失败也落审计（requirements §4-18）：account_id 为空，不暴露身份是否存在
            auditWriter.write(AuthLoginEvent.failure(
                    UUID.randomUUID(), AuthLoginEvent.LoginMethod.WECHAT,
                    provider.name(), requestIp, userAgent, clock.instant()));
            throw new DomainException(ErrorCode.OAUTH_STATE_INVALID, "ticket 无效或已过期，请重新扫码");
        }
        TicketPayload payload = found.get();

        OAuthIdentity identity = authMethods
                .findIdentity(payload.provider(), payload.providerUserId())
                .orElseThrow(() -> new DomainException(
                        ErrorCode.OAUTH_STATE_INVALID, "微信身份未绑定，请先注册"));

        Instant now = clock.instant();
        authMethods.saveIdentity(identity.touchLogin(now));

        Account account = accounts.findById(identity.accountId())
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "账号不存在"));
        auditWriter.write(new AuthLoginEvent(
                UUID.randomUUID(), account.id(), AuthLoginEvent.LoginMethod.WECHAT,
                identity.provider(), true, requestIp, userAgent, now));
        return authService.issue(account);
    }

    /** 登录后绑定微信：需当前密码 + 未绑定的 registration_ticket。 */
    @Transactional
    public void bindWechat(M1Principal principal, String ticket, String currentPassword) {
        Account account = requireAccount(principal);
        if (!passwordHasher.matches(currentPassword, account.passwordHash())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "密码错误");
        }

        TicketPayload payload = stateStore.findRegistrationTicket(ticket)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.OAUTH_STATE_INVALID, "ticket 无效或已过期，请重新扫码"));

        authMethods.findIdentity(payload.provider(), payload.providerUserId())
                .filter(existing -> !existing.accountId().equals(account.id()))
                .ifPresent(existing -> {
                    throw new DomainException(
                            ErrorCode.OAUTH_IDENTITY_TAKEN, "该微信已绑定其他账号");
                });

        Instant now = clock.instant();
        authMethods.saveIdentity(OAuthIdentity.wechat(
                UUID.randomUUID(), account.id(), payload.providerUserId(), payload.unionId(),
                payload.email(), payload.nickname(), payload.avatarUrl(), now));
        stateStore.deleteRegistrationTicket(ticket);
        log.info("微信绑定成功 account_id={}", account.id().value());
    }

    /** 解绑微信：需当前密码；密码仍在，不会失去全部登录方式。 */
    @Transactional
    public void unbindWechat(M1Principal principal, String currentPassword) {
        Account account = requireAccount(principal);
        if (!passwordHasher.matches(currentPassword, account.passwordHash())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "密码错误");
        }
        if (authMethods.findIdentityByAccount(account.id(), provider.name()).isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "尚未绑定微信");
        }
        authMethods.deleteIdentity(account.id(), provider.name());
    }

    private Account requireAccount(M1Principal principal) {
        return accounts.findById(principal.accountId())
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "账号不存在"));
    }

    public record QrView(String state, String authorizeUrl, Instant expiresAt) {
    }

    public record StatusView(String status, String ticket) {
    }
}
