# 2026-11-16 M1 V15 `event_type` 与绑定/解绑审计

> 范围：M1 账号 / 认证；跨模块仅修复测试编译阻塞（M3 测试文件）。
> 授权：用户 2026-11-16 以架构 owner / 全项目整合身份确认采用方案 A，并要求继续代码迭代。
> 状态：实现完成，M1 定向测试执行中；结果待本文件 §5 更新。

## 1. 背景

FR-M1-11 / A25 要求邮箱、微信的绑定 / 解绑写审计，但 `m1_auth.auth_login_events` 只有 `method`（password / email / wechat）与 `success`，无法区分 login / bind / unbind。设计文档原先将 V15 标为“待架构 owner 确认”。

2026-11-16 决策：采用方案 A，新增 `event_type` 列 + 检查约束 + 索引；二维码继续使用现有 `OAuthProvider` 端口抽象，`MockWeChatProvider` 为当前实现，`WeChatOAuthProvider` 保留为后续真实实现。

## 2. 数据库迁移

新增 `server/src/main/resources/db/migration/V15__m1_audit_event_type.sql`：

```sql
ALTER TABLE m1_auth.auth_login_events
    ADD COLUMN event_type varchar(16) NOT NULL DEFAULT 'login';

ALTER TABLE m1_auth.auth_login_events
    ADD CONSTRAINT ck_event_type
        CHECK (event_type IN ('login', 'bind', 'unbind'));

ALTER TABLE m1_auth.auth_login_events
    ADD CONSTRAINT ck_event_method
        CHECK (event_type = 'login' OR method IN ('email', 'wechat'));

CREATE INDEX idx_auth_login_events_type
    ON m1_auth.auth_login_events (event_type, created_at);
```

要点：旧数据默认 `login`；`ck_login_method` 不变；bind / unbind 只允许 email / wechat。

## 3. 代码变更

| 文件 | 变更 |
|---|---|
| `domain/AuthLoginEvent.java` | 新增 `EventType`（LOGIN / BIND / UNBIND），record 增加 `eventType`；新增 `bind(...)` / `unbind(...)` 工厂；登录失败工厂保持 `accountId=null` |
| `infrastructure/persistence/AuthMethodRepositoryAdapter.java` | `EVENT_MAPPER` 读取 `event_type`；insert SQL 增加 `event_type` |
| `application/AuthApplicationService.java` | 密码登录成功事件补 `EventType.LOGIN` |
| `application/EmailCodeService.java` | 邮箱登录成功补 `LOGIN`；`bindEmail` / `unbindEmail` 增加 IP / UA 参数，成功与失败均写 `BIND` / `UNBIND` 审计 |
| `application/OAuthLoginService.java` | 微信登录成功补 `LOGIN`；`bindWechat` / `unbindWechat` 增加 IP / UA 参数，成功与失败均写 `BIND` / `UNBIND` 审计 |
| `api/EmailCodeController.java` / `api/OAuthController.java` | 绑定 / 解绑入口传入客户端 IP 与 User-Agent |
| `application/LoginAuditWriter.java` | 注释更新为认证审计（登录 / 绑定 / 解绑），`REQUIRES_NEW` 语义不变 |
| `server/src/test/.../SessionEndPersistenceFailureIT.java` | 修复 M3 测试构造器歧义：`Random::new` → `() -> new Random()`，使全部测试可编译；不改变行为 |

审计语义：

- 登录失败：`account_id = null`，避免泄露账号存在性。
- 绑定 / 解绑：操作者已登录，`account_id` 必填；成功与失败均记录，`method` 为 email / wechat，微信 `provider=wechat`。
- 审计写入使用独立事务，主事务回滚不会丢失失败事件。

## 4. 测试

测试文件：

- `server/src/test/java/com/shijiangtai/m1auth/api/LoginAuditIT.java`
  - 现有登录事件断言补 `event_type='login'`；
  - 新增邮箱绑定成功、邮箱解绑失败审计断言；
  - `auditTableHoldsNoSecrets` 补 `event_type` 列。
- `server/src/test/java/com/shijiangtai/m1auth/api/EmailCodeIT.java`
  - 邮箱绑定成功 / 密码错误失败 / 解绑成功 / 解绑失败均断言审计行。
- `server/src/test/java/com/shijiangtai/m1auth/api/OAuthWeChatIT.java`
  - 微信绑定成功 / 密码错误失败 / 解绑成功 / 重复解绑失败均断言审计行。

执行命令（本机无 Java / Gradle，使用 Compose test profile）：

```bash
docker compose --profile test run --rm test \
  gradle test --tests 'com.shijiangtai.m1auth.*' --no-daemon --console=plain
```

## 5. 测试结果

> 待定向测试完成后填写：执行时间、通过 / 失败数、失败用例与原因。

## 6. 文档同步

- `docs/design/12-数据库设计.md`：V15 从“待确认”改为“已冻结”；迁移树与 V15 说明更新。
- `docs/requirements/M1-账号管理与身份认证.md`：版本 V0.4，FR-M1-11 / A25 标注 V15 已实现。
- `docs/team/02-进度看板.md`：M1 行移除“待 event_type 契约”，新增更新记录。
- `docs/design/02` / `03` / `10`：原有 `event_type` 描述与实现一致，无需改动。

## 7. 未覆盖 / 风险

- 真实微信开放平台链路仍未实现；Mock provider 验收口径不变。
- unionid 优先关联仍为 P1；P0 唯一键为 `(provider, provider_user_id)`。
- M2—M7 的 URL 权限仍未切到 `api_permissions`。
- 既有问题：邮箱验证码失败尝试计数在抛出 `DomainException` 时会随主事务回滚，可能导致尝试次数不持久；不在本次 V15 范围，建议单独立项。
- 全项目 G2 复审（B1—B10、FR 映射、跨文档引用）未在本轮完成；本轮只冻结 M1 V15 契约。
