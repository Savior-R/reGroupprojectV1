package com.shijiangtai.m1auth.infrastructure.oauth;

import com.shijiangtai.m1auth.application.port.OAuthProvider;
import com.shijiangtai.m1auth.domain.OAuthIdentity;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 微信开放平台 PC 扫码（{@code snsapi_login}，FR-M1-10）。
 *
 * <p>appid / secret 只从服务端配置读取；access_token 用完即弃，不落库、不返回前端。
 */
public class WeChatOAuthProvider implements OAuthProvider {

    private static final String AUTHORIZE_ENDPOINT =
            "https://open.weixin.qq.com/connect/qrconnect";
    private static final String ACCESS_TOKEN_ENDPOINT =
            "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final String USERINFO_ENDPOINT = "https://api.weixin.qq.com/sns/userinfo";

    private final String appId;
    private final String appSecret;
    private final String redirectUri;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WeChatOAuthProvider(
            String appId, String appSecret, String redirectUri, ObjectMapper json) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.redirectUri = redirectUri;
        this.json = json;
    }

    @Override
    public String name() {
        return OAuthIdentity.PROVIDER_WECHAT;
    }

    @Override
    public boolean enabled() {
        return appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    @Override
    public String authorizeUrl(String state) {
        return AUTHORIZE_ENDPOINT
                + "?appid=" + encode(appId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code&scope=snsapi_login"
                + "&state=" + encode(state)
                + "#wechat_redirect";
    }

    @Override
    public OAuthUserInfo exchange(String code) {
        JsonNode token = readJson(ACCESS_TOKEN_ENDPOINT
                + "?appid=" + encode(appId)
                + "&secret=" + encode(appSecret)
                + "&code=" + encode(code)
                + "&grant_type=authorization_code");
        requireNoError(token);

        String accessToken = text(token, "access_token");
        String openId = text(token, "openid");
        String unionId = optionalText(token, "unionid");

        JsonNode profile = readJson(USERINFO_ENDPOINT
                + "?access_token=" + encode(accessToken)
                + "&openid=" + encode(openId));
        requireNoError(profile);

        return new OAuthUserInfo(
                OAuthIdentity.PROVIDER_WECHAT,
                openId,
                optionalText(profile, "unionid") != null ? optionalText(profile, "unionid") : unionId,
                null,
                optionalText(profile, "nickname"),
                optionalText(profile, "headimgurl"));
    }

    private void requireNoError(JsonNode node) {
        JsonNode errcode = node.get("errcode");
        if (errcode != null && errcode.asInt() != 0) {
            throw new OAuthRejectedException(
                    "微信返回错误 errcode=" + errcode.asInt() + " errmsg=" + optionalText(node, "errmsg"));
        }
    }

    private JsonNode readJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new OAuthUnavailableException("微信接口返回 " + response.statusCode());
            }
            return json.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new OAuthUnavailableException("微信接口不可用", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new OAuthRejectedException("微信返回缺少字段 " + field);
        }
        return value.asString();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
