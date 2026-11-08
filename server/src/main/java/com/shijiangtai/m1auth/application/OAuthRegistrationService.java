package com.shijiangtai.m1auth.application;

import com.shijiangtai.m1auth.application.port.OAuthStateStore;
import com.shijiangtai.m1auth.application.port.OAuthStateStore.TicketPayload;
import com.shijiangtai.m1auth.domain.AuthMethodRepository;
import com.shijiangtai.m1auth.domain.OAuthIdentity;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth 引导注册（FR-M1-11）。
 *
 * <p>账号、角色档案与 {@code oauth_identities} 在同一事务内创建；任一步失败整体回滚，
 * ticket 不消耗。同一微信身份已绑定其他账号返回 409。
 */
@Service
public class OAuthRegistrationService {

    private final OAuthStateStore stateStore;
    private final AuthMethodRepository authMethods;
    private final AuthApplicationService authService;
    private final Clock clock;

    public OAuthRegistrationService(
            OAuthStateStore stateStore,
            AuthMethodRepository authMethods,
            AuthApplicationService authService,
            Clock clock) {
        this.stateStore = stateStore;
        this.authMethods = authMethods;
        this.authService = authService;
        this.clock = clock;
    }

    @Transactional
    public AuthApplicationService.AuthResult registerWithWechat(
            OAuthRegisterCommand command, String requestIp, String userAgent) {
        TicketPayload payload = stateStore.findRegistrationTicket(command.ticket())
                .orElseThrow(() -> new DomainException(
                        ErrorCode.OAUTH_STATE_INVALID, "ticket 无效或已过期，请重新扫码"));

        if (authMethods.findIdentity(payload.provider(), payload.providerUserId()).isPresent()) {
            throw new DomainException(
                    ErrorCode.OAUTH_IDENTITY_TAKEN, "该微信已绑定其他账号，请直接扫码登录");
        }

        // 复用注册校验与建档逻辑（同一事务，失败整体回滚）
        AuthApplicationService.AuthResult result = authService.register(
                new AuthApplicationService.RegisterCommand(
                        command.accountType(),
                        command.username(),
                        command.password(),
                        command.email(),
                        command.schoolId(),
                        command.classId(),
                        command.majorIds() == null ? List.of() : command.majorIds()),
                requestIp,
                userAgent);

        AccountId accountId = new AccountId(result.me().accountId());
        authMethods.saveIdentity(OAuthIdentity.wechat(
                UUID.randomUUID(),
                accountId,
                payload.providerUserId(),
                payload.unionId(),
                payload.email(),
                payload.nickname(),
                payload.avatarUrl(),
                clock.instant()));

        // 注册成功后才消耗 ticket
        stateStore.deleteRegistrationTicket(command.ticket());
        return result;
    }

    /** 引导注册命令：ticket + 与普通注册同构的账号信息。 */
    public record OAuthRegisterCommand(
            String ticket,
            String accountType,
            String username,
            String password,
            String email,
            UUID schoolId,
            UUID classId,
            List<UUID> majorIds) {
    }
}
