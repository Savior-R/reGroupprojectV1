package com.shijiangtai.m1auth.application.port;

/**
 * 第三方 OAuth provider 端口（03 §2.1「认证扩展端口」）。
 *
 * <p>只暴露授权 URL、code 换 token、拉取用户信息；provider secret 只在服务端配置，
 * access token 不落库、不返回前端（FR-M1-10）。
 */
public interface OAuthProvider {

    /** provider 标识，与 {@code oauth_identities.provider} 一致。 */
    String name();

    /** 是否为可用的真实实现；false 时上层走 Mock 分支。 */
    boolean enabled();

    /** 生成授权 URL（微信 PC 扫码 {@code snsapi_login}）。 */
    String authorizeUrl(String state);

    /**
     * 用授权码换取用户信息。
     *
     * @throws OAuthUnavailableException provider 不可用 / 网络失败
     * @throws OAuthRejectedException    授权码无效或被拒绝
     */
    OAuthUserInfo exchange(String code);

    /** provider 返回的稳定身份信息；昵称头像仅用于展示。 */
    record OAuthUserInfo(
            String provider,
            String providerUserId,
            String unionId,
            String email,
            String nickname,
            String avatarUrl) {
    }

    class OAuthUnavailableException extends RuntimeException {
        public OAuthUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public OAuthUnavailableException(String message) {
            super(message);
        }
    }

    class OAuthRejectedException extends RuntimeException {
        public OAuthRejectedException(String message) {
            super(message);
        }
    }
}
