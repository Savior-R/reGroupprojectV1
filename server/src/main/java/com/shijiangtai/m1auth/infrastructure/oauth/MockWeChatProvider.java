package com.shijiangtai.m1auth.infrastructure.oauth;

import com.shijiangtai.m1auth.application.port.OAuthProvider;
import com.shijiangtai.m1auth.domain.OAuthIdentity;

/**
 * 开发 / 测试用微信 provider（{@code m1.wechat.mock=true}）。
 *
 * <p>约定：{@code code} 直接作为 {@code provider_user_id}，便于测试构造不同身份；
 * 便于覆盖「已绑定登录 / 未绑定引导注册 / 重复绑定」等分支，无需真实微信资质。
 */
public class MockWeChatProvider implements OAuthProvider {

    @Override
    public String name() {
        return OAuthIdentity.PROVIDER_WECHAT;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String authorizeUrl(String state) {
        return "http://localhost:8080/mock-wechat/authorize?state=" + state;
    }

    @Override
    public OAuthUserInfo exchange(String code) {
        if (code == null || code.isBlank()) {
            throw new OAuthRejectedException("授权码为空");
        }
        // code 形如 "mock-openid-1"，unionId 取同一后缀以便测试 unionid 关联
        String openId = code.startsWith("mock-") ? code : "mock-" + code;
        return new OAuthUserInfo(
                OAuthIdentity.PROVIDER_WECHAT,
                openId,
                "union-" + openId,
                null,
                "微信用户" + openId.substring(Math.max(0, openId.length() - 1)),
                null);
    }
}
