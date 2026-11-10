package com.shijiangtai.m1auth.api;

import com.shijiangtai.m1auth.api.dto.AuthRequests.BindEmailRequest;
import com.shijiangtai.m1auth.api.dto.AuthRequests.EmailCodeRequest;
import com.shijiangtai.m1auth.api.dto.AuthRequests.EmailLoginRequest;
import com.shijiangtai.m1auth.api.dto.AuthRequests.UnbindRequest;
import com.shijiangtai.m1auth.application.AuthApplicationService;
import com.shijiangtai.m1auth.application.EmailCodeService;
import com.shijiangtai.m1auth.application.M1PrincipalProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 邮箱验证码登录与邮箱绑定（FR-M1-09、FR-M1-11）。
 *
 * <p>{@code /email/code} 与 {@code /login/email} 公开；{@code /email/bind/**} 与
 * {@code DELETE /email} 需登录，由 SecurityConfig 精确控制，不用通配放行。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class EmailCodeController {

    private final EmailCodeService emailCodes;
    private final M1PrincipalProvider principals;

    public EmailCodeController(EmailCodeService emailCodes, M1PrincipalProvider principals) {
        this.emailCodes = emailCodes;
        this.principals = principals;
    }

    /** 统一返回 202，不提示邮箱是否存在（02 §4.1.1）。 */
    @PostMapping("/email/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Boolean> sendLoginCode(
            @Valid @RequestBody EmailCodeRequest request, HttpServletRequest http) {
        emailCodes.sendLoginCode(request.email(), AuthController.clientIp(http));
        return Map.of("accepted", true);
    }

    @PostMapping("/login/email")
    public AuthApplicationService.AuthResult loginWithEmail(
            @Valid @RequestBody EmailLoginRequest request, HttpServletRequest http) {
        return emailCodes.loginWithEmail(
                request.email(),
                request.code(),
                AuthController.clientIp(http),
                http.getHeader("User-Agent"));
    }

    /** 向待绑定邮箱发送验证码；需登录。 */
    @PostMapping("/email/bind/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Boolean> sendBindCode(
            @Valid @RequestBody EmailCodeRequest request, HttpServletRequest http) {
        emailCodes.sendBindCode(request.email(), AuthController.clientIp(http));
        return Map.of("accepted", true);
    }

    /** 绑定 / 换绑；需当前密码 + 验证码。 */
    @PostMapping("/email/bind")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bind(
            @Valid @RequestBody BindEmailRequest request) {
        emailCodes.bindEmail(
                principals.require(), request.email(), request.code(), request.currentPassword());
    }

    /** 解绑邮箱；需当前密码。 */
    @DeleteMapping("/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@Valid @RequestBody UnbindRequest request) {
        emailCodes.unbindEmail(principals.require(), request.currentPassword());
    }
}
