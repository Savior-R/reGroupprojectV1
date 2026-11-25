package com.shijiangtai.m1auth.application;

import com.shijiangtai.m1auth.domain.AuthLoginEvent;
import com.shijiangtai.m1auth.domain.AuthMethodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 认证审计写入（FR-M1-11 第 6 条、requirements §4-18、M1-T15、A25）。
 *
 * <p>覆盖登录（login）、绑定（bind）、解绑（unbind）三类事件（V15）。
 *
 * <p><b>必须在主事务提交之后写入</b>：{@code auth_login_events.account_id} 外键指向
 * {@code accounts(id)}（V13）。若在外层事务（注册插入账号 / 绑定、解绑更新
 * {@code accounts.email}）未提交时以 {@code REQUIRES_NEW} 写审计，外键校验需要锁父行，
 * 而父行正被同线程的外层事务持有，形成跨连接的线程自死锁——表现为绑定/解绑请求永久挂起
 * （2026-11-24 测试环境实测：EmailCodeIT 卡在 unbind 审计 INSERT，PostgreSQL 侧
 * {@code wait_event=transactionid} 长达 20 分钟）。
 *
 * <p>因此这里改为：存在活动事务时注册 {@link TransactionSynchronization}，
 * 提交后写审计；回滚时（登录失败等）在 {@code afterCompletion} 中补写失败事件，
 * 保证「失败也落审计、审计不被主事务回滚吞掉」的契约；无事务时直接写入。
 * 单条 INSERT 走自动提交，不依赖外层事务。
 *
 * <p>审计失败不影响认证主流程（03 §3.10）；不记录 token / 验证码 / secret。
 */
@Component
public class LoginAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(LoginAuditWriter.class);

    private final AuthMethodRepository authMethods;

    public LoginAuditWriter(AuthMethodRepository authMethods) {
        this.authMethods = authMethods;
    }

    public void write(AuthLoginEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeDirect(event);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        // 主事务回滚（登录失败等）：审计仍需落库，失败事件不被回滚吞掉
                        writeDirect(event);
                    }
                }
            });
        } else {
            writeDirect(event);
        }
    }

    /** 实际写入；无外层事务，单条 INSERT 自动提交。失败仅告警，不影响主流程。 */
    private void writeDirect(AuthLoginEvent event) {
        try {
            authMethods.insertLoginEvent(event);
        } catch (RuntimeException e) {
            log.warn("登录审计写入失败 method={} success={}", event.method(), event.success());
        }
    }
}
