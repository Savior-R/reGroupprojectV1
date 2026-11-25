package com.shijiangtai.m1auth.application;

import com.shijiangtai.m1auth.domain.AuthLoginEvent;
import com.shijiangtai.m1auth.domain.AuthMethodRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 我的登录记录（只读，03 §3.9 {@code /settings/security}）。
 *
 * <p>只返回当前账号自己的事件；字段严格限于 12 §3.1 的审计列，
 * 不含 token / 验证码 / 微信 access token（03 §3.10）。
 */
@Service
public class SecurityEventService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final AuthMethodRepository methods;

    public SecurityEventService(AuthMethodRepository methods) {
        this.methods = methods;
    }

    /** 分页上限兜底：{@code limit} 缺省 20，最大 50，避免一次拉全表。 */
    @Transactional(readOnly = true)
    public List<SecurityEventView> recent(M1Principal principal, Integer limit) {
        int effective = limit == null || limit <= 0
                ? DEFAULT_LIMIT
                : Math.min(limit, MAX_LIMIT);
        return methods.findRecentEvents(principal.accountId().value(), effective).stream()
                .map(SecurityEventView::of)
                .toList();
    }

    public record SecurityEventView(
            UUID id,
            String eventType,
            String method,
            String provider,
            boolean success,
            String ip,
            String userAgent,
            Instant createdAt) {

        static SecurityEventView of(AuthLoginEvent event) {
            return new SecurityEventView(
                    event.id(),
                    event.eventType().dbValue(),
                    event.method().dbValue(),
                    event.provider(),
                    event.success(),
                    event.ip(),
                    event.userAgent(),
                    event.createdAt());
        }
    }
}
