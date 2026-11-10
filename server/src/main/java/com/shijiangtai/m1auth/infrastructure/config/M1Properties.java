package com.shijiangtai.m1auth.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M1 配置（03 §3.2 / §3.10）。真实值来自 {@code server/.env}（不进仓库）或环境变量。
 */
@ConfigurationProperties(prefix = "m1")
public record M1Properties(
        Jwt jwt,
        EmailCode emailCode,
        Mail mail,
        Wechat wechat,
        Oauth oauth) {

    /** JWT：HS256，2 小时（02 §2.1）。 */
    public record Jwt(String secret, Duration ttl) {
    }

    /** 邮箱验证码：5 分钟、5 次尝试、60 秒重发、邮箱 5 次/小时、IP 20 次/小时（FR-M1-09）。 */
    public record EmailCode(
            String secret,
            Duration ttl,
            int maxAttempts,
            Duration resendInterval,
            int emailHourlyLimit,
            int ipHourlyLimit) {
    }

    /** 邮件：mock=true 走 MockMailSender，不真实发信。 */
    public record Mail(boolean mock, String from) {
    }

    /** 微信开放平台：无资质时 mock=true 走 MockWeChatProvider。 */
    public record Wechat(String appId, String appSecret, String redirectUri, boolean mock) {
    }

    /** OAuth state / ticket 有效期，默认 5 分钟。 */
    public record Oauth(Duration stateTtl, Duration ticketTtl) {
    }
}
