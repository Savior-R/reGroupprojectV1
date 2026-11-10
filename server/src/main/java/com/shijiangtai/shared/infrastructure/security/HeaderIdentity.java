package com.shijiangtai.shared.infrastructure.security;

import java.util.UUID;

/**
 * 测试专用身份（{@link TestHeaderAuthFilter} 注入）。
 *
 * <p>仅供 {@code test} profile 使用，让既有集成测试沿用 {@code X-Account-*} 请求头，
 * 无需为 JWT 改造 116 个用例；生产环境该 filter 不注册。
 */
public record HeaderIdentity(UUID accountId, String accountType, UUID schoolId) {
}
