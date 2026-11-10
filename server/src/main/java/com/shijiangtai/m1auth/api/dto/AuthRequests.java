package com.shijiangtai.m1auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** M1 认证请求体（02 §4.1，JSON snake_case 由全局 Jackson 配置）。 */
public final class AuthRequests {

    private AuthRequests() {
    }

    /**
     * 注册（FR-M1-01）。学生需 {@code classId}，教师需 {@code majorIds}，管理员两者皆空。
     */
    public record RegisterRequest(
            @NotBlank(message = "账号类型必填") String accountType,
            @NotBlank(message = "用户名必填") @Size(max = 64, message = "用户名不能超过 64 个字符")
                    String username,
            @NotBlank(message = "密码必填") String password,
            String email,
            UUID schoolId,
            UUID classId,
            List<UUID> majorIds) {
    }

    /** 用户名 + 密码登录（FR-M1-02）。 */
    public record LoginRequest(
            @NotBlank(message = "用户名必填") String username,
            @NotBlank(message = "密码必填") String password) {
    }

    /** 发送邮箱验证码（FR-M1-09）；{@code purpose} 为 login 或 bind。 */
    public record EmailCodeRequest(
            @NotBlank(message = "邮箱必填") String email,
            String purpose) {
    }

    /** 邮箱验证码登录（FR-M1-09）。 */
    public record EmailLoginRequest(
            @NotBlank(message = "邮箱必填") String email,
            @NotBlank(message = "验证码必填") String code) {
    }

    /** 绑定邮箱（FR-M1-11）：需验证码 + 当前密码。 */
    public record BindEmailRequest(
            @NotBlank(message = "邮箱必填") String email,
            @NotBlank(message = "验证码必填") String code,
            @NotBlank(message = "当前密码必填") String currentPassword) {
    }

    /** 解绑邮箱 / 微信（FR-M1-11）：需当前密码。 */
    public record UnbindRequest(
            @NotBlank(message = "当前密码必填") String currentPassword) {
    }

    /** 微信扫码 ticket 换 JWT（FR-M1-10）。 */
    public record ExchangeRequest(
            @NotBlank(message = "ticket 必填") String ticket) {
    }

    /** OAuth 引导注册（FR-M1-11）：与 {@link RegisterRequest} 同构，额外带 ticket。 */
    public record OAuthRegisterRequest(
            @NotBlank(message = "ticket 必填") String ticket,
            @NotBlank(message = "账号类型必填") String accountType,
            @NotBlank(message = "用户名必填") @Size(max = 64, message = "用户名不能超过 64 个字符")
                    String username,
            @NotBlank(message = "密码必填") String password,
            String email,
            UUID schoolId,
            UUID classId,
            List<UUID> majorIds) {
    }
}
