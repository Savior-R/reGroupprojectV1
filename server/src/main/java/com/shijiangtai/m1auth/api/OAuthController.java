package com.shijiangtai.m1auth.api;

import com.shijiangtai.m1auth.api.dto.AuthRequests.ExchangeRequest;
import com.shijiangtai.m1auth.api.dto.AuthRequests.OAuthRegisterRequest;
import com.shijiangtai.m1auth.api.dto.AuthRequests.UnbindRequest;
import com.shijiangtai.m1auth.application.AuthApplicationService;
import com.shijiangtai.m1auth.application.M1PrincipalProvider;
import com.shijiangtai.m1auth.application.OAuthLoginService;
import com.shijiangtai.m1auth.application.OAuthRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信开放平台扫码登录 / 引导注册 / 绑定（FR-M1-10、FR-M1-11）。
 *
 * <p>qr / callback / status / exchange / register 公开；bind 与 DELETE 需登录。
 */
@RestController
@RequestMapping("/api/v1/auth/oauth/wechat")
public class OAuthController {

    private final OAuthLoginService oauth;
    private final OAuthRegistrationService registrations;
    private final M1PrincipalProvider principals;

    public OAuthController(
            OAuthLoginService oauth,
            OAuthRegistrationService registrations,
            M1PrincipalProvider principals) {
        this.oauth = oauth;
        this.registrations = registrations;
        this.principals = principals;
    }

    @GetMapping("/qr")
    public OAuthLoginService.QrView qr() {
        return oauth.createQr();
    }

    /** 微信回调；服务端换 openid / unionid，不返回 token。 */
    @GetMapping("/callback")
    public Map<String, Boolean> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state) {
        oauth.handleCallback(code, state);
        return Map.of("accepted", true);
    }

    @GetMapping("/status")
    public OAuthLoginService.StatusView status(@RequestParam String state) {
        return oauth.status(state);
    }

    @PostMapping("/exchange")
    public AuthApplicationService.AuthResult exchange(
            @Valid @RequestBody ExchangeRequest request, HttpServletRequest http) {
        return oauth.exchange(
                request.ticket(),
                AuthController.clientIp(http),
                http.getHeader("User-Agent"));
    }

    /** 引导注册：账号 + 档案 + 微信身份同事务（FR-M1-11）。 */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthApplicationService.AuthResult register(
            @Valid @RequestBody OAuthRegisterRequest request, HttpServletRequest http) {
        return registrations.registerWithWechat(
                new OAuthRegistrationService.OAuthRegisterCommand(
                        request.ticket(),
                        request.accountType(),
                        request.username(),
                        request.password(),
                        request.email(),
                        request.schoolId(),
                        request.classId(),
                        request.majorIds()),
                AuthController.clientIp(http),
                http.getHeader("User-Agent"));
    }

    /** 登录后绑定微信：需当前密码 + 扫码得到的 registration_ticket。 */
    @PostMapping("/bind")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bind(@Valid @RequestBody BindWechatRequest request) {
        oauth.bindWechat(
                principals.require(), request.ticket(), request.currentPassword());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@Valid @RequestBody UnbindRequest request) {
        oauth.unbindWechat(principals.require(), request.currentPassword());
    }

    /** 绑定请求：ticket + 当前密码。 */
    public record BindWechatRequest(String ticket, String currentPassword) {
    }
}
