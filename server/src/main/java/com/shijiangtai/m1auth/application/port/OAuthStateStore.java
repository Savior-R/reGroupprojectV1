package com.shijiangtai.m1auth.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * OAuth 扫码状态与一次性 ticket 存储（03 §3.10）。
 *
 * <p>生产用 Redis，测试 profile 用内存实现；state / ticket 均 5 分钟、一次性。
 * login_ticket 取走即失效；registration_ticket 需保留到引导注册成功后才删除。
 */
public interface OAuthStateStore {

    /** 新建扫码会话，状态 {@code pending}。 */
    void create(String state, Duration ttl);

    Optional<ThreadState> status(String state);

    void markScanned(String state);

    /** 已绑定身份：写入 login_ticket，状态变 {@code authorized}。 */
    void markAuthorized(String state, String ticket, TicketPayload payload, Duration ttl);

    /** 未绑定身份：写入 registration_ticket，状态变 {@code needs_registration}。 */
    void markNeedsRegistration(String state, String ticket, TicketPayload payload, Duration ttl);

    void markFailed(String state);

    /** 状态为 {@code authorized} 时取走 login_ticket（一次性），返回其携带的身份。 */
    Optional<TicketPayload> takeLoginTicket(String ticket);

    /** 引导注册前读取 registration_ticket（不消耗，事务失败可重试）。 */
    Optional<TicketPayload> findRegistrationTicket(String ticket);

    void deleteRegistrationTicket(String ticket);

    /** 扫码状态（02 §4.1：pending / scanned / authorized / needs_registration / expired / failed）。 */
    enum Status {
        PENDING,
        SCANNED,
        AUTHORIZED,
        NEEDS_REGISTRATION,
        EXPIRED,
        FAILED;

        public String wireValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** state 的当前状态与（可能已生成的）ticket。 */
    record ThreadState(Status status, String ticket) {
    }

    /** registration_ticket 携带的微信身份。 */
    record TicketPayload(
            String provider,
            String providerUserId,
            String unionId,
            String email,
            String nickname,
            String avatarUrl) {
    }
}
