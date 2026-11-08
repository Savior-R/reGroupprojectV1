package com.shijiangtai.m1auth.application;

import com.shijiangtai.m1auth.domain.AuthLoginEvent;
import com.shijiangtai.m1auth.domain.AuthMethodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录审计写入（FR-M1-11 第 6 条、requirements §4-18、M1-T15、A25）。
 *
 * <p><b>必须使用独立事务</b>：密码 / 邮箱登录失败会抛 {@code DomainException} 回滚主事务；
 * 若审计与主事务共用事务，刚写入的失败事件会被一并回滚，表现为「登录失败从不落审计」。
 * 因此这里用 {@link Propagation#REQUIRES_NEW} 先提交审计，再让主事务回滚。
 *
 * <p>审计失败不影响登录主流程（03 §3.10）；不记录 token / 验证码 / secret。
 */
@Component
public class LoginAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(LoginAuditWriter.class);

    private final AuthMethodRepository authMethods;

    public LoginAuditWriter(AuthMethodRepository authMethods) {
        this.authMethods = authMethods;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuthLoginEvent event) {
        try {
            authMethods.insertLoginEvent(event);
        } catch (RuntimeException e) {
            log.warn("登录审计写入失败 method={} success={}", event.method(), event.success());
        }
    }
}
