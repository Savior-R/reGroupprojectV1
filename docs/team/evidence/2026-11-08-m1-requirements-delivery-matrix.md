# M1 需求实现 × 分工覆盖 对照报告

> 对照输入
> - `docs/requirements/M1-账号管理与身份认证.md` **V0.3（2026-11-07，待评审）**
> - `docs/team/prompts/01-foundation-m1.md`（Foundation Owner 启动提示词）
>
> 代码基线：`feat/m1-auth`（自 `main@d972384` 拉出，31 项未提交变更）
> 生成时间：2026-11-08　生成者：ρRe（Foundation Owner，兼前端共享层 / E2E）
>
> **修订记录**
> - 17:20 初版（FR 7 已实现 / 4 部分）
> - 17:45 增量：补前端导入 UI（FR-M1-04 闭环）、修复「登录失败审计被事务回滚」缺陷、补 `LoginAuditIT` 5 用例、补微信登录失败审计。FR 变为 **8 已实现 / 3 部分**，M1 用例 **81 / 0 失败**。

---

## 0. 一页结论

### 0.1 功能需求（FR-M1-01 — FR-M1-11）

| 状态 | 数量 | 编号 |
|---|---|---|
| ✅ 已实现 | **8** | FR-M1-01、02、03、**04**、05、06、08、09 |
| 🟡 部分实现 | **3** | FR-M1-07（权限码未表驱动）、FR-M1-10（unionid 未用于关联、真实微信未验证）、FR-M1-11（绑定/解绑审计缺契约支撑、解绑最后方式在当前模型不可达） |
| ❌ 未实现 | **0** | — |

> FR-M1-04 已于 17:45 补齐前端导入入口（原为「后端有、前端无」）。

### 0.2 验收用例（docs/design/10 §6.1 M1-T01—T15）

| 状态 | 数量 | 编号 |
|---|---|---|
| ✅ 已被自动化覆盖 | **9** | T01、T02、T03、T04、T05、T06、T11、T12、T13 |
| 🟡 部分覆盖 | **5** | T07（课程侧属 M2）、T09 / T10（仅后端断言，无 E2E）、T14（解绑最后方式不可达，需文档定口径）、T15（登录审计已补断言；绑定/解绑审计待 `event_type` 契约） |
| ❌ 未覆盖 | **1** | T08（账号抽屉只读 E2E） |

### 0.3 分工覆盖

| 分工条款 | 覆盖情况 |
|---|---|
| §5.1 P0 目标 6 项 | **6/6 全部交付** |
| §6 执行顺序 9 步 | **7 完成 / 2 部分**（第 7 步 `auth:self` 未校验、第 9 步 E2E 未做） |
| §3 允许编辑路径 9 项 | 7 项已用；`frontend/src/stores/auth*` **未动**；`docs/team/evidence/*m1*` **本次创建** |
| §7 测试策略 | UT / IT 完成；**E2E 未做**（提示词允许后置，但 M1-T08—T10 属 E2E） |
| §9 反模式 4 条 | 3 条遵守；**1 条越界**（修改 owner 表，见 §9.2） |

### 0.4 三个必须先处理的结论

1. **有 2 份文档被越界修改**（`docs/team/01`、`CLAUDE.md`），提示词 §3 明文禁止。虽经用户口头授权，但按 `team/01` §3.3 末行需全体 owner 追认，否则 PR 阶段会被打回。
2. **`auth:self` 权限码只有种子数据、没有校验实现**。`SecurityConfig` 注释自述「URL 级细粒度权限（api_permissions 表驱动）为后续接入」，与 FR-M1-07「接口级角色与学校范围授权」的完整意图有差距。
3. **解绑最后一个登录方式未做校验**（FR-M1-11 / 验收第 12 条 / M1-T14）。当前因"密码不可清空"该规则不可达，但一旦后续支持清空密码就会失守。

---

## 1. 交付内容与规模（§8 第 1、3 项）

### 1.1 分支与变更

- 分支：`feat/m1-auth`（自 `main@d972384` 拉出，未提交、未推送）
- 变更：**29 个路径**（15 修改 + 12 新增 + 2 删除，`git status --short` 口径，含目录）

### 1.2 代码规模

| 层 | 文件 | 行数 | 用例 |
|---|---|---|---|
| `m1auth` 主代码 | 59 | 5 074 | — |
| `m1auth` 测试 | 11 | 1 865 | **81** |
| 前端 M1 页面 | 7 | 1 643 | — |
| 前端 M1 API / JWT | 2 | 429 | — |
| 迁移 `V13` | 1 | 110 | — |
| Nginx 网关模板 | 1 | 108 | — |

### 1.3 对外端点（8 个 Controller / 30 个端点）

| 端点组 | 方法 + 路径 | 鉴权 |
|---|---|---|
| 注册登录 | `POST /api/v1/auth/register`、`POST /login`、`GET /me` | 前两个公开，`/me` 需 JWT |
| 邮箱验证码 | `POST /auth/email/code`、`POST /auth/login/email`、`POST /auth/email/bind/code`、`POST /auth/email/bind`、`DELETE /auth/email` | 前两个公开，后三个需 JWT |
| 微信扫码 | `GET /auth/oauth/wechat/qr`、`GET /callback`、`GET /status`、`POST /exchange`、`POST /register`、`POST /bind`、`DELETE /auth/oauth/wechat` | qr/callback/status/exchange/register 公开，bind/unbind 需 JWT |
| 建校 | `POST /admin/schools`、`GET /admin/school`、`PATCH /admin/school` | ROLE_ADMIN |
| 组织 | `GET|POST /admin/majors`、`PATCH /admin/majors/{id}`、`GET|POST /admin/classes`、`PATCH /admin/classes/{id}` | ROLE_ADMIN |
| 导入 | `POST /admin/majors/import`、`POST /admin/classes/import`、`GET /admin/imports/templates/{type}` | ROLE_ADMIN |
| 教师 | `GET /admin/teachers`、`PUT /admin/teachers/{id}/majors` | ROLE_ADMIN |
| 公开选项 | `GET /public/schools`、`/schools/{id}/majors`、`/schools/{id}/grades`、`/public/classes` | 公开 |
| 内部认证 | `GET /internal/auth/verify` | `X-Internal-Token` |

---

## 2. 功能需求逐条对照（FR-M1-01 — FR-M1-11）

### FR-M1-01 注册（P0）— ✅ 已实现

| 需求条款（原文摘录） | 状态 | 实现证据 |
|---|---|---|
| 三角色共用注册入口，必须选择并确认账号类型，注册后不可变更 | ✅ | `AuthController.java:31`；`AuthApplicationService.register` 按 `AccountType` 分支；`Account` 无类型更新方法、无此类端点 |
| 不提供账号类型切换接口或管理入口 | ✅ | 全部 30 个端点无类型变更；`Account.java` 无 `changeType` |
| 用户名即真实姓名、平台内唯一；密码加密存储 | ✅ | `UsernamePolicy`（trim / 64 code point / 拒控制字符）+ `uq_accounts_username`；`BCryptPasswordHasher`（cost 12） |
| 系统生成全局唯一账号 ID，注册成功后展示 | ✅ | `Account.register` 用 `UUID.randomUUID()`；`GET /auth/me` 返回 `account_id` |
| 学生必填学校/年级/专业；教师必填学校 + 多专业；管理员不需学校 | ✅ | `AuthApplicationService` 第 91—104 行三路分支；`requireClass`、`requireMajors` |
| 异常：类型缺失/非法、用户名占用、必填缺失、密码不合规、学校不存在、教师所选学校未导入专业 | ✅ | `USERNAME_TAKEN(409)`、`VALIDATION_ERROR(400)`、`CLASS_NOT_MATCHED(422)`、`MAJOR_DISABLED(422)`；`requireMajors` 校验空列表 / 非本校 / 已停用 |

### FR-M1-02 登录与会话（P0）— ✅ 已实现

| 需求条款 | 状态 | 实现证据 |
|---|---|---|
| 三种登录方式，登录后按角色进对应首页 | ✅ | 密码 `AuthController:48`；邮箱 `EmailCodeController:48`；微信 `OAuthController:64`；前端 `homeFor()` 按 `account_type` 分流 |
| JWT 含 account_id / role / school_id / exp / iat / jti | ✅ | `JwtTokenIssuer`（claims：`sub` / `account_type` / `school_id` / `exp` / `iat` / `jti`）；`JwtTokenIssuerTest` 8 用例 |
| `Authorization: Bearer`；验签、验过期、校验角色 | ✅ | `SecurityConfig:128-132` + `NimbusJwtDecoder`；`JwtTokenIssuerTest.tokenIsNotVerifiableWithAnotherSecret` |
| HS256 + 环境变量密钥 + 2 小时 + 无 refresh | ✅ | `application.yml:69` `ttl: PT2H`；密钥来自 `M1_JWT_SECRET`；无 refresh 端点 |
| 登出由客户端丢弃 token | ✅ | `SecurityView.vue` `logout()` 清 localStorage；无服务端吊销端点 |
| 未建校管理员 `school_id` 为空，仅可访问建校接口 | ✅ | `JwtTokenIssuerTest.adminWithoutSchoolOmitsSchoolClaim`；`OrgAdminIT.adminWithoutSchoolCannotTouchOrg` |
| 建校后返回新 token，不要求重新登录 | ✅ | `SchoolApplicationService:50-70`；`OrgAdminIT.createSchoolReturnsTokenCarryingSchoolId`；实测 token 263→331 字符 |
| 异常：统一提示、401、403 | ✅ | `AuthApplicationService:143-158` 两个失败分支共用同一 `UNAUTHORIZED`；`SecurityConfig` entryPoint 401 / accessDenied 403 |

### FR-M1-03 建校与绑定（P0）— ✅ 已实现

| 需求条款 | 状态 | 实现证据 |
|---|---|---|
| 未绑定管理员可创建一所学校，填写名称 | ✅ | `AdminSchoolController:37`；`SchoolApplicationService.create` |
| 绑定后不能创建第二所、不能管其他学校 | ✅ | `SCHOOL_ALREADY_BOUND(409)`；`OrgAdminIT.secondSchoolIsRejected` |
| 学校名称平台内唯一 | ✅ | `uq_schools_name` + `SchoolApplicationService:56`；`OrgAdminIT.schoolNameMustBeUniqueAcrossPlatform` |
| 创建成功返回新 token | ✅ | `SchoolApplicationService` 重新签发；测试断言 `school_id` claims |
| 异常：已绑定 / 名称为空 / 重名 | ✅ | 已绑定 409；空名与重名 400 `VALIDATION_ERROR`（02 未定义独立码，符合"默认"口径） |

> 说明：需求写"学校名称重复：拒绝创建（默认）"，未指定状态码；实现取 400。若团队要求 409，需在 `02` 增补错误码后调整。

### FR-M1-04 学校 / 专业 / 班级维护（P0）— ✅ 已实现

| 需求条款 | 状态 | 实现证据 / 缺口 |
|---|---|---|
| 管理员可创建、导入、编辑本校学校信息、专业、班级 | ✅ | 后端：`POST/PATCH /admin/majors`、`/admin/classes`、`PATCH /admin/school`、两个 `/import`；前端：`OrgView.vue` 已补「导入 CSV / Excel」上传 + 「下载模板」+ 失败明细弹窗（17:45 补齐） |
| CSV / Excel 导入，自然键幂等，整批事务，任一行失败整批回滚 | ✅ | `OrgImportService.parseCsv / parseXlsx`（POI 6）；`@Transactional`；`OrgAdminIT.importWithInvalidRowWritesNothing` 断言失败后 0 条写入 |
| 重复、缺失、跨校引用校验 | ✅ | `importClassesRejectsUnknownMajor`；重复行幂等更新；空值 / 超长逐行报错 |
| 专业 / 班级停用不物理删除，停用后不出现在新选项 | ✅ | `ActiveStatus` + `PATCH .../{id}`；`majorAndClassCrudThenPublicOptionsHideDisabled` |
| 班级已有学生时不能改专业/年级，空班级可改 | ✅ | `OrgApplicationService:123-147` 抛 `VALIDATION_ERROR`「班级已有学生，不能修改专业或年级」 |
| 非管理员只读，不提供增删改入口 | ✅ | `/api/v1/admin/**` → `hasRole("ADMIN")`；`OrgAdminIT.studentCannotAccessAdminEndpoints` |

### FR-M1-05 学生归属与班级匹配（P0）— ✅ 已实现

| 需求条款 | 状态 | 实现证据 |
|---|---|---|
| 填学校、年级、专业后查询该校该年级该专业的班级 | ✅ | `PublicOrgController:41` `GET /public/classes?school_id&major_id&grade` |
| 恰好一个自动入班；多个由学生选；没有则无法完成 | ✅ | `RegisterView.vue:94` `form.classId = classes.length === 1 ? classes[0].id : ''`；`classes.length === 0` 时提示且提交被拦（第 119 行） |
| 班级数据只读，学生不能自行改班 | ✅ | 无改班端点；`PROFILE` 仅注册时写入 |

### FR-M1-06 教师归属（P0）— ✅ 已实现

| 需求条款 | 状态 | 实现证据 |
|---|---|---|
| 注册选择学校 + 一个或多个本校专业，不绑年级 | ✅ | `requireMajors`（去重、本校、启用）；`AuthControllerIT.registerTeacherKeepsSelectedMajors` |
| 教师本人不能改自己专业，管理员可改 | ✅ | 无自助端点；`PUT /admin/teachers/{id}/majors` 仅 ADMIN；`OrgAdminIT.adminUpdatesTeacherMajorsAndTeacherCannotSelfEdit` |
| 多年级多专业参与课程（M2 体现） | n/a | 属 M2 |

### FR-M1-07 接口授权与学校范围（P0）— 🟡 部分实现

| 需求条款 | 状态 | 实现证据 / 缺口 |
|---|---|---|
| 公开接口精确白名单（注册、三种登录、发码、扫码/回调/状态/exchange、引导注册、选项查询） | ✅ | `SecurityConfig` `PUBLIC_POST` / `PUBLIC_GET` 精确到方法 + 路径，**未用通配放行绑定/解绑** |
| 学生访问本人档案、班级与选课基础数据 | ✅ | `GET /auth/me` 返回 `student` 视图含 grade / major / class |
| 教师访问本人档案与本校基础数据 | ✅ | `GET /auth/me` 返回 `teacher` 视图含专业列表 |
| 管理员维护本校；未建校仅可建校 | ✅ | `ROLE_ADMIN` + 未建校时 `OrgApplicationService` 抛 `FORBIDDEN` |
| 受保护接口需有效 token；跨校一律拒绝 | ✅ | `CROSS_SCHOOL_ACCESS(403)`，见 `OrgApplicationService:136,202`、`TeacherMajorService:83` |
| **接口级权限（`api_permissions` 表驱动 / `auth:self`）** | 🟡 | `V13` 已种 5 条路由 + 15 条角色映射，但 **Java 侧无 `PermissionAuthorizationManager` 实现**；`SecurityConfig:31` 注释自述「URL 级细粒度权限为后续接入，当前用角色粗粒度兜底」 |

> 影响：功能上不越权（角色 + 服务内校域校验已覆盖）；**契约上`auth:self` 是空转的**，若后续按 `api_permissions` 做动态授权，这层需补。

### FR-M1-08 管理员修改教师专业范围（P0）— ✅ 已实现

| 需求条款 | 状态 | 实现证据 |
|---|---|---|
| 管理员可改本校教师专业；教师本人不可 | ✅ | `AdminTeacherController:35`；`TeacherMajorService` |
| 至少保留一个有效专业，且必须是本校启用专业 | ✅ | `TeacherMajorService:76` 抛「至少保留一个有效专业」；`:83` 跨校拒绝 |
| 旧关系标记失效并保留历史，不物理删除 | ✅ | `replaceActiveMajors` 置 `inactive` + `valid_to`；前端提示「旧关系保留为历史」 |
| 只能改本校教师，跨校拒绝 | ✅ | `CROSS_SCHOOL_ACCESS` |
| 已有课程老师组关系保留（M2） | n/a | 属 M2 |
| 异常：非本校 / 无有效专业 / 专业停用 / 跨校 | ✅ | 四条均有对应异常 |

### FR-M1-09 邮箱验证码登录（P0）— ✅ 已实现

| 需求条款 | 状态 | 实现证据 |
|---|---|---|
| 登录后绑定邮箱；需验证码；平台内唯一、大小写不敏感 | ✅ | `lower(email)` 唯一索引 + `EmailAddress` 统一小写；`EmailCodeService.bindEmail` |
| 登录页「邮箱验证码登录」全流程 | ✅ | `LoginView` 第二 tab；`POST /auth/email/code` → `/auth/login/email` |
| 6 位、5 分钟、单次、只存 HMAC | ✅ | `application.yml:73-74`；`HmacVerificationCodeHasher`（HMAC-SHA256，64 hex）；`consumedCodeCannotBeReplayed` |
| 60 秒重发；邮箱 5 次/时；IP 20 次/时；超限 429 | ✅ | `application.yml:75-77`；`EmailCodeService:118-129` 三处 `RATE_LIMITED`；`resendWithinSixtySecondsIsRateLimited` |
| 同一码最多 5 次尝试；发新码旧码失效 | ✅ | `max-attempts: 5`；`withFailedAttempt`；`isUsable` |
| 仅已绑定并验证邮箱可登录；未绑定/不存在/错误统一 `UNAUTHORIZED` | ✅ | `EmailCodeService:145-163` 四路统一；`sendAcceptsUnknownEmailToAvoidLeakage` |
| 登录成功签发同密码登录 JWT | ✅ | `EmailCodeIT.emailCodeLoginIssuesTokenWithSameClaims` |
| 绑定 / 换绑需验证码；换绑需当前密码；解绑需当前密码 | ✅ | `bindEmail` 校验当前密码 + BIND 码；`unbindEmail` 校验当前密码 |
| 异常：格式非法 / 已绑其他账号 409 / 过期错误超限 / 邮件 503 | ✅ | `EMAIL_TAKEN(409)`；`SERVICE_UNAVAILABLE(503)` `EmailCodeService:108` |

### FR-M1-10 微信开放平台扫码（P0）— 🟡 部分实现

| 需求条款 | 状态 | 实现证据 / 缺口 |
|---|---|---|
| P0 只支持 PC 扫码 `snsapi_login` | 🟡 | `WeChatOAuthProvider` 按官方协议实现（qrconnect + sns/oauth2/access_token）；**真实链路未验证**：`M1_WECHAT_APP_ID` 为空、`M1_WECHAT_MOCK=true` |
| 生成一次性 state + 授权 URL；5 分钟、单次 | ✅ | `InMemoryOAuthStateStore` / `RedisOAuthStateStore`；`state-ttl: PT5M`；`unknownStateReportsExpired` |
| 回调校验 state，换 openid / unionid；access token 不落库 | ✅ | `WeChatOAuthProvider` 只返回 `OAuthUserInfo`；`OAuthIdentity` 无 token 字段 |
| 已绑定 → login_ticket（5 分钟）→ 换 JWT | ✅ | `loginTicketIsSingleUse`；`ticket-ttl: PT5M` |
| 未绑定 → registration_ticket → 引导注册，不静默建号 | ✅ | `unboundIdentityGoesToRegistrationThenLogsInDirectly` |
| 登录标识 `provider + provider_user_id`，**优先 unionid 关联** | 🟡 | 存了 `union_id`，但查找登录身份时按 `(provider, provider_user_id)`；**未实现"unionid 优先合并同一开放平台账号"**（`OAuthLoginService:92` 只写入） |
| 轮询 6 状态：pending / scanned / authorized / needs_registration / expired / failed；可刷新 | ✅ | `StatusView` + `markScanned`；前端 `OAuthStatus` 六值全映射，`LoginView:115-137`；过期可重新 `loadQr` |
| 异常：state 过期/重放、回调失败、provider 不可用、ticket 过期 → 统一失败；503 | ✅ | `OAUTH_STATE_INVALID(400)`、`OAUTH_PROVIDER_UNAVAILABLE(503)`；不签发 JWT |

> 说明：Mock 模式下 `code` 即 openid，全链路（扫码→回调→状态→ticket→登录/注册）有 8 个集成用例覆盖。**"unionid 优先关联"是真实多应用场景才需要的语义，当前缺实现**，需在文档中降级或补代码。

### FR-M1-11 OAuth 引导注册与账号绑定（P0）— 🟡 部分实现

| 需求条款 | 状态 | 实现证据 / 缺口 |
|---|---|---|
| 引导注册必须选类型 + 补齐对应必填项 | ✅ | `OAuthRegistrationService`；`/register/oauth` 页面三身份分支 |
| 必须设用户名、密码 + 可选邮箱；唯一性校验 | ✅ | 复用 `UsernamePolicy` / `EmailAddress` / `USERNAME_TAKEN` / `EMAIL_TAKEN` |
| 账号 + `oauth_identities` 同事务，失败回滚 | ✅ | `OAuthRegistrationService` `@Transactional`；`registrationTicketCannotBeReusedAfterSuccess` |
| 已登录可绑定 / 解绑微信与邮箱，绑定前重新验证，解绑需当前密码 | ✅ | `OAuthController:93,100`、`EmailCodeController:68,77`；`bindWechatToExistingAccountRequiresPassword`、`unbindWechatRequiresPassword` |
| 一个 `provider_user_id` 只绑一个账号；同 provider 单绑定；重复 409 | ✅ | `UNIQUE(provider, provider_user_id)` + `UNIQUE(account_id, provider)`；`wechatIdentityCannotBindTwoAccounts`；`OAUTH_IDENTITY_TAKEN(409)` |
| **绑定 / 解绑写审计事件** | 🟡 | 微信 / 邮箱的绑定、解绑**均未写** `auth_login_events`；原因是该表无事件类型列（`ck_login_method` 只允许 password/email/wechat），需 `V14` 加 `event_type` 后落地（escalate §1.5）。**登录**审计已完整：密码 / 邮箱 / 微信的成功与失败四路都写，且失败事件已修复「被事务回滚」缺陷 |
| 解绑最后一个登录方式时拒绝 | ❌ | 当前模型不可达：`Account.passwordHash` 强制非空（`Account.java:32`）、DB 列非空、本轮不支持清空密码。建议改文档口径（escalate §4.1），而非写死代码 |
| 异常：ticket 过期/已用、类型缺失、学校/专业/班级无效、冲突、身份已绑、解绑唯一方式 | 🟡 | 除「解绑唯一方式」外全部有；该分支不可达 → 未写 |

---

## 3. 业务规则 §4 对照（18 条）

| # | 规则 | 状态 | 依据 |
|---|---|---|---|
| 1 | 三角色自助注册，类型不可变，不做审核 | ✅ | FR-M1-01 |
| 2 | 用户名即姓名、平台唯一、重名加后缀 | ✅ | `UsernamePolicy` + 唯一索引 |
| 3 | 账号 ID 系统生成、平台唯一、资料展示 | ✅ | `UUID` + `/auth/me` |
| 4 | 管理员与学校一对一 | ✅ | `SCHOOL_ALREADY_BOUND` + 唯一约束 |
| 5 | 管理员维护本校学校信息、导入专业班级 | ✅ | 后端 + 前端导入入口均已具备 |
| 6 | 学生匹配班级：一个自动、多个自选、无则失败 | ✅ | `RegisterView:94,119` |
| 7 | 年级来自班级，学生只选不输 | ✅ | 年级选项由 `/public/schools/{id}/grades` 提供，无手工输入 |
| 8 | 教师绑学校 + 多专业，不绑年级 | ✅ | `requireMajors`；`teacher_profiles` 无 grade |
| 9 | JWT 带三要素，按类型与校域授权 | ✅ | `JwtTokenIssuer` + `SecurityConfig` |
| 10 | 管理员不改他人账号，但可改本校教师专业 | ✅ | 无账号管理端点 |
| 11 | 学校/专业/班级属 M1；课程属 M2 | ✅ | 边界未越 |
| 12 | 三种登录签同一套 claims | ✅ | `emailCodeLoginIssuesTokenWithSameClaims` |
| 13 | 邮箱码只用于已绑定验证邮箱账号 | ✅ | 查 `email_verified_at` |
| 14 | 微信只接 PC 扫码；已绑直登、未绑引导、不静默建号 | 🟡 | Mock 全通；真实 appid 缺 |
| 15 | 引导注册必须补齐信息；建号 + 绑定同事务 | ✅ | `@Transactional` |
| 16 | 可绑/解绑邮箱与微信；需重新验证；一个邮箱/微信只绑一个账号 | ✅ | 见 FR-M1-11 |
| 17 | 验证码与 state/ticket 一次性；只存哈希；token/secret 不落库 | ✅ | `consumedCodeCannotBeReplayed`、`loginTicketIsSingleUse`；`V13` 无明文列 |
| 18 | 所有登录失败统一提示；成功/失败写审计 | 🟡 | 统一提示 ✅；审计 ✅（password/email/wechat）；**邮箱绑定/解绑未写审计** |

---

## 4. 权限矩阵 §7 对照（11 行）

| 操作 | 需求 | 状态 | 依据 |
|---|---|---|---|
| 注册 / 登录 | 三角色可 | ✅ | 公开白名单 |
| 邮箱验证码登录 | 已绑邮箱可 | ✅ | `email_verified_at` 校验 |
| 微信扫码 / 引导注册 | 三角色可 | ✅ | 公开白名单 |
| 绑定 / 解绑邮箱微信 | 本人可 | ✅ | `auth:self` 语义由 JWT 主体限制为本人 |
| 创建学校（未绑定） | 仅管理员 | ✅ | `ROLE_ADMIN` |
| 维护本校学校/专业/班级 | 仅管理员 | ✅ | `ROLE_ADMIN` |
| 查看本校基础数据 | 学生（注册/本人）、教师、管理员 | ✅ | `/auth/me` + 公开选项 |
| 学生班级匹配 | 学生本人 | ✅ | 注册时写入 |
| 管理其他账号（专业范围除外） | 均不可 | ✅ | 无端点 |
| 修改本校教师专业范围 | 仅管理员 | ✅ | `PUT /admin/teachers/{id}/majors` |
| 跨校数据 | 均不可 | ✅ | `CROSS_SCHOOL_ACCESS` |

---

## 5. 异常与边界 §8 对照（22 条）

| # | 异常 | 状态 | 实现 |
|---|---|---|---|
| 1 | 用户名重复 → 阻止 + 提示加后缀 | ✅ | `USERNAME_TAKEN(409)` |
| 2 | 密码不合规 / 必填缺失 → 阻止 | ✅ | `PasswordPolicy` / `VALIDATION_ERROR` |
| 3 | 学校不存在 → 阻止 | ✅ | `requireSchool` |
| 4 | 教师所选学校未导入专业 → 阻止 | ✅ | `requireMajors` 空列表 |
| 5 | 已绑学校再创建 → 拒绝 | ✅ | 409 |
| 6 | 未建校访问其他管理接口 → 拒绝 | ✅ | `FORBIDDEN` + 测试 |
| 7 | 无匹配班级 → 注册无法完成 | ✅ | `CLASS_NOT_MATCHED(422)` |
| 8 | 多个匹配班级 → 必须选一 | ✅ | 前端必填校验 |
| 9 | 专业/班级停用 → 不出现在新选项，已有关系保留 | ✅ | 只查 `active` |
| 10 | 已有学生班级改专业/年级 → 阻止 | ✅ | `OrgApplicationService:147` |
| 11 | 改教师专业：非本校 / 空 / 停用 / 跨校 → 拒绝 | ✅ | `TeacherMajorService` |
| 12 | 学校名称重复 → 拒绝 | ✅ | `VALIDATION_ERROR` |
| 13 | token 无效/过期 401；角色/跨校 403 | ✅ | `SecurityConfig` + `CROSS_SCHOOL_ACCESS` |
| 14 | 邮箱未绑/不存在/码错/过期/超限 → `UNAUTHORIZED`；超频 429；邮件 503 | ✅ | 四路统一 + `RATE_LIMITED` + `SERVICE_UNAVAILABLE` |
| 15 | 邮箱重复绑定 409 统一文案；换绑/解绑缺密码拒绝 | ✅ | `EMAIL_TAKEN` + `unauthenticated()` |
| 16 | 微信 state/ticket 过期、重放、已用、provider 失败 → 统一失败；未绑不建号 | ✅ | `OAUTH_STATE_INVALID` |
| 17 | 引导注册各项非法 → 整笔回滚 | ✅ | `@Transactional` |
| 18 | **解绑最后一个登录方式 → 拒绝** | ❌ | **未实现**（规则不可达） |
| 19 | 登录失败不区分账号/邮箱/微信是否存在 | ✅ | 三处统一文案 |
| 20 | 审计保留内部原因、不含 token/验证码 | ✅ | `auth_login_events` 无 token 列；日志不输出验证码 |
| 21 | 微信昵称头像仅展示 | ✅ | 仅存 `nickname` / `avatar_url` |
| 22 | access token 不落库、不返回前端 | ✅ | `WeChatOAuthProvider` 局部变量 |

---

## 6. 验收要点 §9 对照（13 条）

| # | 验收要点 | 状态 | 说明 |
|---|---|---|---|
| 1 | 管理员注册 → 登录 → 建校 → 导入专业/班级，只能管本校 | ✅ | 全链路可跑；导入已有前端入口 |
| 2 | 学生用导入的学校/年级/专业注册并匹配班级 | ✅ | 实测通过 |
| 3 | 教师注册选学校 + 多专业，不绑年级 | ✅ | 实测通过 |
| 4 | JWT 签发与校验（token / 角色 / 校域） | ✅ | 8 + 7 用例 |
| 5 | 一管理员一学校 | ✅ | 409 + 唯一约束 |
| 6 | 非管理员无法改学校/专业/班级 | ✅ | 403 |
| 7 | 停用后不出现在新选项；已有关系保留；有学生班级不可改 | ✅ | 3 个用例 |
| 8 | 管理员改教师专业；教师本人不可；旧关系失效留史 | ✅ | 1 个综合用例 |
| 9 | 注册必须选类型、不可变更、无切换入口 | ✅ | 无端点 |
| 10 | 邮箱验证码登录 + 限流 + 不泄露 | ✅ | 10 用例 |
| 11 | 微信已绑直登 / 未绑引导注册 | ✅ | 8 用例（Mock） |
| 12 | 同一微信不能绑多账号；绑定/解绑需验证；**解绑最后方式拒绝** | 🟡 | 前两项 ✅；**第三项未实现** |
| 13 | 三种登录 claims 一致；失败不泄露存在性 | ✅ | 专项用例 |

---

## 7. 测试矩阵 M1-T01—T15

### 7.0 最近一次执行（2026-11-08 17:38）

```
命令：gradle test  （SPRING_DATASOURCE_URL → shijiangtai_test，test profile）
全量：777 用例 / 0 失败 / 0 跳过 / 104 个测试类    BUILD SUCCESSFUL in 4m 20s
M1  ： 81 用例 / 0 失败 / 11 个测试类
```

| 测试类 | 用例 | 失败 |
|---|---|---|
| `AuthControllerIT` | 7 | 0 |
| `EmailCodeIT` | 10 | 0 |
| `OrgAdminIT` | 12 | 0 |
| `OAuthWeChatIT` | 8 | 0 |
| `InternalAuthIT` | 6 | 0 |
| `LoginAuditIT` | 5 | 0 |
| `JwtTokenIssuerTest` | 8 | 0 |
| `PasswordPolicyTest` | 7 | 0 |
| `UsernamePolicyTest` | 6 | 0 |
| `EmailAddressTest` | 6 | 0 |
| `HmacVerificationCodeHasherTest` | 6 | 0 |

> 上一轮（17:14）的唯一失败 `WsHeartbeatIT.pongResponseKeepsConnectionOpen()` 本次通过，确认为并发时序 flaky。
> 更早一次执行因 Docker Desktop 停止导致全部 IT 无法加载 context，恢复容器后正常——非代码问题。

### 7.1 逐条覆盖

| 编号 | 用例 | 状态 | 证据（测试类 / 方法） |
|---|---|---|---|
| M1-T01 | 三角色注册；类型确认后不可变 | ✅ | `AuthControllerIT` × 3（student / admin / teacher） |
| M1-T02 | 用户名唯一、密码强度、BCrypt cost 12 | ✅ | `PasswordPolicyTest`(10)、`UsernamePolicyTest`(6)、`duplicateUsernameIsRejected`、`BCryptPasswordHasher(12)` |
| M1-T03 | 注册后自动登录；HS256 2h；过期 401 | ✅ | `JwtTokenIssuerTest.expiresAfterTwoHours` 等 8 项 |
| M1-T04 | 登录失败不泄露账号是否存在 | ✅ | `loginReturnsTokenAndWrongPasswordIsIndistinguishable` |
| M1-T05 | 建校、一对一、重复 409 | ✅ | `createSchoolReturnsTokenCarryingSchoolId`、`secondSchoolIsRejected`、`schoolNameMustBeUniqueAcrossPlatform` |
| M1-T06 | 组织 CRUD；CSV/XLSX 导入；错误行回滚 | ✅ | `OrgAdminIT` 导入 4 项（CSV 与 XLSX 共用 `parse`）+ 前端 `OrgView` 上传与错误明细 |
| M1-T07 | 教师专业范围；**课程创建校验** | 🟡 | M1 侧 3 项已测；**课程创建属 M2，未验证** |
| M1-T08 | 账号抽屉只读；无改资料/改密码接口 | ❌ | **无 E2E**；后端确无此类端点（`AuthController` 仅 3 个） |
| M1-T09 | 未绑定管理员强制 `/admin/setup` | 🟡 | 前端守卫已实现（`router.ts:139`），**无 E2E 断言** |
| M1-T10 | 学生/教师访问管理端 403 | 🟡 | 后端 403 有测；**前端路由守卫无 E2E** |
| M1-T11 | 邮箱码发送/登录/过期/重放/尝试上限/限流/未绑定统一失败 | ✅ | `EmailCodeIT` 10 项 |
| M1-T12 | 微信 state/callback/ticket；已绑登录、未绑引导、state 重放 | ✅ | `OAuthWeChatIT` 8 项（Mock provider） |
| M1-T13 | 引导注册校验；账号与身份同事务；重复身份 409 | ✅ | 3 项 |
| M1-T14 | 绑定/解绑；换绑验证；**解绑最后方式拒绝**；三种登录 claims 一致 | 🟡 | 前两项 + claims ✅；解绑最后方式在当前模型不可达，待文档定口径（见 escalate 清单 §4.1） |
| M1-T15 | 审计 method/provider/success/ip/UA；不含验证码/token；不泄露 | 🟡 | 登录审计已补 `LoginAuditIT` 5 项（密码/邮箱成功失败 + 表结构无敏感列）；**绑定/解绑审计需 `event_type` 契约**，见 escalate 清单 §1.5 |

---

## 8. 分工覆盖（对照 01-foundation-m1.md）

### 8.1 §5.1 P0 目标（6 项）

| # | 目标 | 状态 |
|---|---|---|
| 1 | FR-M1-01—08 注册/建校/组织/教师专业 | ✅ |
| 2 | FR-M1-09 邮箱绑定、发送/校验/登录/限流/尝试上限 | ✅ |
| 3 | FR-M1-10 微信 PC 扫码 state/callback/status/login_ticket | ✅（Mock 可跑，真实未验） |
| 4 | FR-M1-11 引导注册同事务；绑定/解绑 | 🟡（缺解绑最后方式） |
| 5 | 三种登录同 JWT（HS256 2h，claims 不变）；登录审计 | ✅ |
| 6 | 前端 `/login` 三 tab、`/register/oauth`、`/settings/security` | ✅ |

### 8.2 §6 执行顺序（9 步）

| 步 | 内容 | 状态 |
|---|---|---|
| 1 | 契约确认（按 02/03/12 编码） | ✅ |
| 2 | 迁移 V13（email 两列、三张表、索引/FK） | ✅ 已在 test 库验证 |
| 3 | 领域 / JPA（Account、OAuthIdentity、EmailLoginCode、AuthLoginEvent） | ✅ 改 JDBC 适配器实现（`15 §2.9` 允许） |
| 4 | 邮箱验证码（MailSender SMTP + Mock、发送/校验/登录/限流） | ✅ |
| 5 | 微信扫码（Provider、Redis store、callback/status/exchange/绑定） | ✅ |
| 6 | OAuth 引导注册（复用校验、同事务、失败回滚） | ✅ |
| 7 | Security / 审计（白名单、`auth:self`、审计、日志不输出敏感值） | 🟡 **`auth:self` 未校验**；其余 ✅ |
| 8 | 前端（三 tab、轮询、倒计时、引导注册、账号安全页） | ✅（缺导入 UI，不属本节） |
| 9 | 测试 / 证据 / 看板（M1-T01—T15；只更新 M1 行与证据） | 🟡 E2E 缺 1 条、部分覆盖 5 条；证据本次补；**看板之外还动了 01 / CLAUDE** |

### 8.3 §3 允许编辑路径（9 项）

| # | 路径 | 是否使用 | 说明 |
|---|---|---|---|
| 1 | `server/.../m1auth/**` | ✅ | 58 文件 5 027 行 |
| 2 | `server/src/test/.../m1auth/**` | ✅ | 10 文件 81 用例 |
| 3 | `V1*__m1_*.sql` | ✅ 未改 | `V1__m1_auth.sql` 保持原样 |
| 4 | `V13__m1_login_methods.sql` | ✅ 新建 | 13 个迁移全部应用成功 |
| 5 | `frontend/src/views/auth/**` | ✅ | 3 页 |
| 6 | `frontend/src/views/admin/**` | ✅ | 4 页 |
| 7 | `frontend/src/api/auth*` | ✅ | `auth.ts` + `jwt.ts` |
| 8 | `frontend/src/stores/auth*` | ❌ **未动** | 仍为 dev 假身份，M3 前端仍走 `X-Account-*` 头 |
| 9 | `docs/team/evidence/*m1*` | ✅ | 本文件 + CSV |

### 8.4 §3 禁止项偏离（必须追认）

| 文件 | 提示词原文 | 实际情况 | 风险 |
|---|---|---|---|
| `docs/team/01-模块归属与修改规则.md` | 「不得编辑：…`docs/team/01`、owner 表、模块边界…」 | **已修改 3 处**（Foundation / Score-QA / M3 行标注兼任） | 用户口头授权，但按 `team/01 §3.3`「Owner 表变更需全体 owner 确认」，PR 阶段可能被要求撤回或补确认 |
| `CLAUDE.md` | 未列入允许清单 | **已修改**（当前使用者改为 M1 + M3 兼任） | 仓库级规则文件，影响所有 agent 的归属判断 |
| `docs/team/02-进度看板.md` | 「不得编辑…他人进度行」 | 仅改 M1 行 | 合规（本模块进度行） |

### 8.5 §3 白名单外但属 Foundation 共享路径（需架构 owner 确认）

提示词 §3 原文：「`shared/**`、`application*.yml`、`V0*`/`V9*`/`ops` schema 属于 Foundation 共享路径，**但改前仍需按 `docs/team/01` 说明影响并让架构 owner 确认**」。

| 路径 | 改动 | 是否已确认 |
|---|---|---|
| `shared/domain/ErrorCode.java` | 新增 12 个 M1 错误码 | ❌ 架构 owner 空缺 |
| `shared/infrastructure/security/**` | 新建 SecurityConfig / InternalAuthController / InternalTokenFilter / HeaderIdentity / TestHeaderAuthFilter | ❌ |
| `shared/infrastructure/web/**` | 异常处理从 m3classroom 上移 | ❌（影响 2 个 M3 测试 import） |
| `application.yml` / `application-test.yml` | M1 配置段 + test 限流放大 | ❌ |
| `build.gradle.kts` | 5 个依赖 | ❌（`team/01 §3.3` 归 Foundation） |
| `docker-compose.yml` | nginx 服务、app 端口 8090、env_file | ❌ |
| `nginx/**` | 新建网关模板 | ❌ |
| `GlobalExceptionHandler` 位置迁移 | 删除 `m3classroom/api/error/**` | ❌（触及 M3 目录，虽只有 2 个测试 import） |

> 这些改动功能上必要（不加 security 依赖无法实现 FR-M1-02/07），但**流程上缺一次显式确认**。建议在 PR 描述中单列「跨模块/共享路径变更」小节并请 M3 Owner 复核 `GlobalExceptionHandler` 迁移。

---

## 9. 三张清单

### 9.1 ✅ 已实现（可直接验收）

1. 三角色注册 + 类型不可变 + 唯一性 + 密码策略（BCrypt 12）
2. 密码登录 + JWT（HS256 / 2h / 五要素 claims）+ Bearer 校验 + 401/403
3. 管理员建校 + 一对一 + 名称唯一 + 返回新 token
4. 学校信息编辑、专业/班级增改启停、CSV/XLSX 导入（整批回滚）、模板下载
5. 学生四级级联选班 + 单班自动选中 + 无班阻断
6. 教师多专业注册 + 管理员改专业范围（旧关系留史）
7. 邮箱验证码全链路（6 位 / 5 分 / 单次 / 5 次尝试 / 60 秒 / 5·20 限流 / 429）
8. 微信扫码全链路（state / callback / status 六态 / ticket / exchange / 绑定解绑），Mock provider 可跑
9. OAuth 引导注册（三身份 + 同事务 + ticket 一次性）
10. 登录审计（password / email / wechat，含 IP / UA）
11. 内部认证端点 `/internal/auth/verify` + `X-Internal-Token` 校验（fail closed）
12. Nginx `auth_request` 网关模板（精确白名单、清除伪造身份头、登录限流）
13. 前端 7 页 + 8 条路由 + 4 条守卫 + JWT 解析

### 9.2 🟡 部分实现（有缺口，可用但不完整）

| 项 | 缺口 | 影响 | 修复量 |
|---|---|---|---|
| FR-M1-07 `auth:self` | 仅 seed，无 Java 校验 | 契约空转；功能不越权 | 1 个 `PermissionAuthorizationManager` + 接线（约 80 行） |
| FR-M1-10 unionid | 只存不用 | 多应用场景无法合并同一开放平台账号 | 若不接真实微信则**不适用**，无需改代码 |
| FR-M1-10 真实微信 | appid 为空走 Mock | 无真实二维码 | **改文档口径（不接）**，见 escalate 清单 §1.1 |
| FR-M1-11 绑定/解绑审计 | `auth_login_events` 无事件类型列 | 审计不完整 | **需 `V14` 加 `event_type`**（契约变更），见 escalate 清单 §1.5 |
| M1-T08/T09/T10 | 无 E2E | 前端守卫无自动化验证 | Playwright 3 条 |
| 前端 `stores/auth.ts` | 仍 dev 假身份 | M3 与 M1 两套身份并存 | 属前端共享层 |

> 已于 17:45 解决：FR-M1-04 前端导入入口、登录审计失败事件丢失（事务回滚缺陷）、登录审计断言测试。

### 9.3 ❌ 未实现（明确缺失）

| 项 | 出处 | 状态 |
|---|---|---|
| 解绑最后一个登录方式拒绝 | FR-M1-11 第 6 条 / §8 第 18 条 / §9 第 12 条 / M1-T14 | 已改口径为「密码必有，场景不可达」（2026-11-08）：`requirements` 198 / 286 / 302 / 331、`design/03` 176 / 527 / 545、`design/10` 192 |
| M1-T08 账号抽屉只读 E2E | design/10 §6.1 | 无 `e2e/` 目录 |
| 顶部导航按角色裁剪 | design/03 §3.9 | `layouts/` 仅 `ImmersiveLayout.vue` |
| CI / 质量门禁 | design/10 §10 | 无 CI 配置 |

---

## 10. 阻塞项与外部依赖

| # | 项 | 类型 | 状态 | 出处 |
|---|---|---|---|---|
| 1 | 微信开放平台 appid / secret / 回调白名单 | 外部资质 | **未获得**（需企业/个体户 + 认证费） | prompts/01 §4、§8 |
| 2 | 首批专业清单数据 | 需求输入 | **未提供**（示例数据已备） | requirements/M1 第 334 行 |
| 3 | SMTP 配额 / SPF-DKIM | 运维 | 已用 QQ 邮箱真发（约 50 封/天），够开发 | prompts/01 §8 |
| 4 | 架构 owner 确认共享路径变更 | 流程 | **未完成**（角色空缺） | prompts/01 §3 |
| 5 | 全体 owner 追认 owner 表变更 | 流程 | **未完成**（角色空缺） | team/01 §3.3 |
| 6 | Docker Desktop 自启 | 环境 | 本次因 Docker 停止导致一轮测试全红，已恢复；建议加 `restart: unless-stopped` | — |

---

## 11. §8 交付物汇报（逐项应答）

| §8 要求 | 应答 |
|---|---|
| 1. 分支、commit、变更文件 | `feat/m1-auth`，**0 commit**（全部工作区改动），25 个路径；清单见 §1.1 |
| 2. Owner、是否跨模块、已获谁确认 | Owner：ρRe（Foundation + 兼任 Score/QA）；跨模块：`shared/**`、`GlobalExceptionHandler` 迁移、`router.ts`；确认：**仅用户口头授权，无架构/Score-QA owner 书面确认** |
| 3. 实现内容与关键设计决定 | 见 §1.3 / §2；关键决定：JdbcTemplate 替代 27 个 JPA 实体、`TestHeaderAuthFilter` 兼容 M3 旧测试、`/internal/**` 收窄到 `/internal/auth/**` |
| 4. 测试命令与结果 | `gradle test`（`SPRING_DATASOURCE_URL` → `shijiangtai_test`，test profile）：**777 用例 / 0 失败 / 0 跳过；M1 81 用例 0 失败**，明细见 §7.0 |
| 5. 证据文件路径 | `docs/team/evidence/2026-11-08-m1-requirements-delivery-matrix.md`（本文件）、`...-tracking.csv` |
| 6. 未完成项、阻塞项、已知风险 | 见 §9.2 / §9.3 / §10 |
| 7. 是否合并 / 推送 | **未合并、未推送**；待用户决定 |

---

## 12. 建议的收尾顺序（更新于 17:45）

| 序 | 动作 | 归属 | 状态 |
|---|---|---|---|
| 1 | `OrgView` 加导入上传 + 错误行表格 | Foundation | ✅ 已完成 |
| 2 | 修复登录失败审计被回滚的缺陷 + 补 `LoginAuditIT` | Foundation | ✅ 已完成 |
| 3 | 「解绑最后一个登录方式」定口径（改文档 2 行，说明密码必填故规则恒成立） | Foundation + 需求方 | ⬜ 待你拍板（escalate §4.1） |
| 4 | 绑定/解绑审计所需 `event_type` 列（`V14` 迁移） | 架构 owner + Foundation | ⬜ 待契约决策（escalate §1.5） |
| 5 | `stores/auth.ts` 换 JWT、`layouts` 加角色导航 | 前端共享层 | ⬜ |
| 6 | Playwright 三条 E2E（T08/T09/T10） | E2E | ⬜ |
| 7 | `PermissionAuthorizationManager` 或 `auth:self` 契约降级 | Foundation + 架构 | ⬜ 待决策（escalate §2.1） |
| 8 | 请 M3 Owner 复核 `GlobalExceptionHandler` 迁移与 `TestHeaderAuthFilter` | 跨模块 | ⬜ 评审 |
| 9 | PR 描述补「共享路径变更 + owner 表变更」并请求追认 | Foundation | ⬜ |

> 代码侧已经没有「需求要求、代码零实现」的功能点。剩余三项（3/4/7）都需要先定口径或改契约，见 `2026-11-08-m1-escalation-list.md`。
