# Foundation Owner — M1 账号、组织与认证 启动提示词

> 模板来源：`docs/team/prompts/template.md`　基线：`main`（开始前 `git fetch origin`，以最新 main 为准）　日期：2026-11-07

你是 **Foundation Owner**，负责 **M1 账号、组织与认证**。先完整阅读本提示词、必读文档和仓库规则，再开始编辑。

## 1. 仓库与分支

- 仓库：`/home/forever_alone/project_school_temp`（GitHub `Savior-R/reGroupprojectV1`）。
- 基线：`main`；开始前 `git fetch origin`，以最新 main 为准，确认 `main` 最新。
- 从 `main` 新建 `feat/m1-auth`，不要使用 M3 的 `feat/m3a-task-config` 或其他进行中分支。
- 工作区可能有他人未提交文件；不得清理、提交或覆盖。提交使用 `git commit --only <显式路径>`。
- 不得在 main 直接开发，不得 force push。

## 2. 必读文档（按顺序）

1. `CLAUDE.md`、`docs/team/01-模块归属与修改规则.md`。
2. `docs/requirements/M1-账号管理与身份认证.md` **V0.3（2026-11-07）**：FR-M1-01—11，尤其 FR-M1-09 邮箱验证码、FR-M1-10 微信扫码、FR-M1-11 OAuth 引导注册与绑定。
3. `docs/design/03-M1-M2-账号组织课程设计.md` **V0.5**：3.2.1—3.2.3、3.10。
4. `docs/design/02-API与实时协议.md` **V1.7**：§2 认证、§4.1.1、§8.2.1 错误码。
5. `docs/design/01-数据模型与迁移.md` **V1.2**、`docs/design/12-数据库设计.md` **V0.2**：`accounts.email`、`oauth_identities`、`email_login_codes`、`auth_login_events`、`V13__m1_login_methods.sql`。
6. `docs/design/10-测试与验收.md` **V0.5** §6.1：M1-T01—T15。
7. `docs/implementation/01-M1-实现设计.md` **V0.2**。
8. `docs/team/02-进度看板.md` M1 行：G2 未关闭，完成度 10%。

## 3. 职责与允许编辑的路径

允许编辑：

```text
server/src/main/java/com/shijiangtai/m1auth/**
server/src/test/java/com/shijiangtai/m1auth/**
server/src/main/resources/db/migration/V1*__m1_*.sql
server/src/main/resources/db/migration/V13__m1_login_methods.sql
frontend/src/views/auth/**
frontend/src/views/admin/**
frontend/src/api/auth*（如需要）
frontend/src/stores/auth*（如需要）
docs/team/evidence/*m1*
```

不得编辑：其他模块 Java / Python / 前端 / 迁移 / 测试、`docs/team/01`、owner 表、模块边界、他人进度行。`shared/**`、`application*.yml`、`V0*`/`V9*`/`ops` schema 属于 Foundation 共享路径，但改前仍需按 `docs/team/01` 说明影响并让架构 owner 确认。

## 4. 当前状态与依赖

- 本模块当前 gate：G1 提交，G2 待复审；测试 0%。
- 上游：无业务依赖。
- 外部依赖：微信开放平台（appid / secret / 回调白名单）、SMTP 邮件服务。
- 契约待冻结：邮箱验证码 API、微信 OAuth state / ticket / callback、`oauth_identities` 唯一键、`auth:self` 权限码、JWT claims 不变。
- M1 新增设计尚未实现；现有 M1 目录可能是早期骨架，先确认现状再改，不要假设已完成。
- **并行开发约定**：M1 可立即开始；G2 评审并行进行，不作为编码硬门禁；M2/M5 按 M1 文档契约 + fake 并行，不需要等 M1 完成。发现契约冲突停下确认。

## 5. 目标与范围

### 5.1 P0 目标

- 实现 FR-M1-01—08 原有注册 / 建校 / 组织 / 教师专业。
- 实现 FR-M1-09：邮箱绑定、验证码发送 / 校验 / 登录 / 限流 / 尝试上限。
- 实现 FR-M1-10：微信开放平台 PC 扫码 `snsapi_login`，state / callback / status / login_ticket。
- 实现 FR-M1-11：未绑定微信的 OAuth 引导注册（账号 + 档案 + 微信身份同事务）；登录后绑定 / 解绑邮箱与微信。
- 三种登录方式签发同一 JWT（HS256 2h，claims 不变）；登录审计。
- 前端 `/login` 三 tab、`/register/oauth`、`/settings/security`。

### 5.2 不做

- 微信以外 provider、手机 / 短信验证码、Passkey / TOTP、密码找回、账号资料修改、账号类型变更。
- 按 OAuth 邮箱静默合并账号。
- Refresh token、服务端会话吊销、跨校账号。
- 修改 M2—M7 代码或数据库表。

### 5.3 必须遵守的契约

- `email` 写入前 `trim` + 小写；`lower(email)` 唯一；`email_verified_at` 为空不可邮箱登录。
- `oauth_identities`：`UNIQUE(provider, provider_user_id)`、`UNIQUE(account_id, provider)`；不存 access token / secret。
- 验证码：6 位、5 分钟、单次、5 次尝试、60 秒重发；只存 HMAC-SHA256。
- state / login_ticket / registration_ticket：Redis（测试用内存），5 分钟、一次性。
- JWT claims：`sub / account_type / school_id / exp / iat / jti`，不新增权限列表。
- 错误码：`EMAIL_TAKEN`、`OAUTH_IDENTITY_TAKEN`、`OAUTH_STATE_INVALID`、`OAUTH_PROVIDER_UNAVAILABLE`；登录失败统一 `UNAUTHORIZED`，不泄露账号 / 邮箱 / 微信身份存在性。
- 绑定 / 解绑需重新验证；解绑最后一个登录方式拒绝。
- 任何契约变更先改 `02` / `03` / `12` 并让架构 owner 确认。

## 6. 执行顺序

1. **契约确认（可并行）**：按 `docs/design/02/03/12` 的 M1 契约编码；G2 评审并行进行；发现契约冲突再停下确认。
2. **迁移 V13**：`accounts.email` / `email_verified_at`、三张新表、索引 / FK；`V13` 不得修改已执行迁移。
3. **领域 / JPA**：`Account` email 验证状态、`OAuthIdentity`、`EmailLoginCode`、`AuthLoginEvent`。
4. **邮箱验证码**：`MailSender`（SMTP + Mock）、发送 / 校验 / 登录、限流、绑定 / 换绑 / 解绑。
5. **微信扫码**：`OAuthProvider` 端口、`WeChatOAuthProvider`、Redis state store、callback、status、exchange、绑定。
6. **OAuth 引导注册**：复用注册校验；账号 + profile + `oauth_identities` 同事务；失败回滚。
7. **Security / 审计**：公开路径精确白名单；`auth:self` 权限；登录审计；不在日志输出验证码 / token。
8. **前端**：三 tab 登录、二维码轮询、邮箱倒计时、OAuth 引导注册、账号安全页。
9. **测试 / 证据 / 看板**：M1-T01—T15；只更新 M1 行和本模块证据。

## 7. 测试策略（本阶段后置）

- 本阶段最小验证：`docker compose --profile test run --rm test gradle compileJava compileTestJava --no-daemon --console=plain` + 登录 / 建校 smoke；前端构建。
- 完整 UT / IT / 契约 / E2E 后置到测试阶段，不阻塞并行开发；后续清单：M1-T01—T15。
- 真实微信 / SMTP 只做手工 smoke；CI 使用 Mock，不得把真实 key 写入仓库。
- 未跑全量必须在汇报中明确“测试后置”，不得伪造结果。

## 8. 交付物与汇报格式

1. 分支、commit 列表、变更文件；
2. Owner、是否跨模块、已获谁确认；
3. 实现内容与关键设计决定；
4. 测试命令与结果（classes / tests / failures / skipped）；
5. 证据文件路径（`docs/team/evidence/*m1*`）；
6. 未完成项、阻塞项、已知风险（微信审核、SMTP 配额、回调域名）；
7. 是否合并 / 推送（默认等用户选择）。

## 9. 反模式

- 不得改 M2—M7 或共享 owner 表来绕过登录依赖。
- 不得把微信 access token、secret、验证码、JWT 写入日志或仓库。
- 不得用“只改测试 / 只改注释”跳过跨模块确认。
- 不得伪造测试结果；失败必须给出命令与输出。
