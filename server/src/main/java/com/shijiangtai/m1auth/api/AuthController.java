package com.shijiangtai.m1auth.api;

import com.shijiangtai.m1auth.api.dto.AuthRequests.LoginRequest;
import com.shijiangtai.m1auth.api.dto.AuthRequests.RegisterRequest;
import com.shijiangtai.m1auth.application.AuthApplicationService;
import com.shijiangtai.m1auth.application.M1PrincipalProvider;
import com.shijiangtai.m1auth.application.SecurityEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 注册 / 密码登录 / 当前账号 / 我的登录记录（FR-M1-01、FR-M1-02、FR-M1-11、M1 §3.8）。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthApplicationService auth;
    private final SecurityEventService securityEvents;
    private final M1PrincipalProvider principals;

    public AuthController(
            AuthApplicationService auth,
            SecurityEventService securityEvents,
            M1PrincipalProvider principals) {
        this.auth = auth;
        this.securityEvents = securityEvents;
        this.principals = principals;
    }

    /** 注册成功直接签发 JWT（03 §3.1「注册与登录」）。 */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthApplicationService.AuthResult register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return auth.register(
                new AuthApplicationService.RegisterCommand(
                        request.accountType(),
                        request.username(),
                        request.password(),
                        request.email(),
                        request.schoolId(),
                        request.classId(),
                        request.majorIds()),
                clientIp(http),
                http.getHeader("User-Agent"));
    }

    @PostMapping("/login")
    public AuthApplicationService.AuthResult login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return auth.login(
                new AuthApplicationService.LoginCommand(request.username(), request.password()),
                clientIp(http),
                http.getHeader("User-Agent"));
    }

    @GetMapping("/me")
    public AuthApplicationService.AccountView me() {
        return auth.me(principals.require());
    }

    /** 我的登录记录（只读）：成功 / 失败的登录与绑定事件，供 /settings/security 展示。 */
    @GetMapping("/security/events")
    public List<SecurityEventService.SecurityEventView> securityEvents(
            @RequestParam(name = "limit", required = false) Integer limit) {
        return securityEvents.recent(principals.require(), limit);
    }

    /** Nginx 在网关层写 X-Forwarded-For；直连时退回 remoteAddr。 */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
