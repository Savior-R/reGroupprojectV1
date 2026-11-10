# 02 API 与实时协议

> 上位文档：[需求规格说明书](../requirements/需求规格说明书.md)、[00 总体设计](00-总体设计.md)、[01 数据模型与迁移](01-数据模型与迁移.md)
> 版本：V1.7（讨论稿）　状态：待评审　更新日期：2026-11-07
> 范围：M1—M7；M8 暂缓
> 风格：REST / JSON + WebSocket + Redis Streams；Java 提供公开 API，Python 只提供内部 AI API

## 1. 通用约定

- 基础路径：`/api/v1`；JSON；UTF-8；字段统一 `snake_case`（Java DTO 配置 `PropertyNamingStrategies.SNAKE_CASE`）。
- ID：UUID 字符串。
- 时间：REST 使用 ISO 8601 UTC；课堂事件 / WS 使用 session 相对毫秒 `at_ms`。
- HTTP 方法：GET 查询、POST 创建 / 动作、PUT 整体更新、PATCH 局部更新、DELETE 删除或退出。
- 认证：`Authorization: Bearer <JWT>`；WebSocket 使用短时效 ticket。
- 公开接口：注册、密码 / 邮箱验证码登录、邮箱验证码发送、微信扫码 / 回调 / 状态 / ticket exchange、注册选项、健康检查；其余默认需要认证。

## 2. 认证与网关

### 2.1 JWT

- HS256；有效期 2 小时；不做 refresh token。
- Claims：`sub`（account_id）、`roles`（授权角色数组，如 `["teacher","admin"]`）、`account_type`（主身份：student / teacher / admin）、`school_id`（可空）、`exp`、`iat`、`jti`。
- Java 使用 Spring Security 7.1 + OAuth2 Resource Server 校验 JWT；`/api/**` 为 stateless resource server；`/internal/**` 使用单独 filter chain，要求内部 token。
- URL 级权限由自定义 `AuthorizationManager` 查询 `api_permissions` + `account_type_permissions`；**token 的 `roles` 可多值，判定取各角色权限的并集**（教师兼管理员，V14）；service 层仍做资源级权限。
- 管理员未绑定学校时 `school_id` 为空；建校后返回新 token。
- 用户名密码、邮箱验证码、微信扫码三种登录方式签发同一 claims 的 JWT；OAuth / 邮箱均不改变 token 有效期与角色范围。

### 2.2 Nginx `auth_request`

```nginx
# 公开接口：不经过 auth_request
location = /api/v1/auth/login               { proxy_pass http://java_app; }
location = /api/v1/auth/login/email         { proxy_pass http://java_app; }
location = /api/v1/auth/email/code          { proxy_pass http://java_app; }
location = /api/v1/auth/oauth/wechat/qr     { proxy_pass http://java_app; }
location = /api/v1/auth/oauth/wechat/callback { proxy_pass http://java_app; }
location = /api/v1/auth/oauth/wechat/status { proxy_pass http://java_app; }
location = /api/v1/auth/oauth/wechat/exchange { proxy_pass http://java_app; }
location = /api/v1/auth/oauth/wechat/register { proxy_pass http://java_app; }
location = /api/v1/auth/register            { proxy_pass http://java_app; }
location ^~ /api/v1/public/                 { proxy_pass http://java_app; }
location = /actuator/health      { proxy_pass http://java_app; }

# 受保护 API
location /api/ {
    auth_request /_auth;

    auth_request_set $account_id   $upstream_http_x_account_id;
    auth_request_set $account_type $upstream_http_x_account_type;
    auth_request_set $school_id    $upstream_http_x_school_id;

    proxy_set_header X-Account-Id   $account_id;
    proxy_set_header X-Account-Type $account_type;
    proxy_set_header X-School-Id    $school_id;

    proxy_pass http://java_app;
}

# WebSocket 不走 auth_request；Java 握手 GETDEL 校验 ticket
location /ws/ {
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;
    proxy_buffering off;
    proxy_pass http://java_app;
}

# 内部接口不暴露公网
location /internal/ { deny all; }

location = /_auth {
    internal;
    proxy_pass http://java_app/internal/auth/verify;
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header X-Original-URI $request_uri;
    proxy_set_header X-Internal-Token $INTERNAL_SERVICE_TOKEN;
}
```

- Java `/internal/auth/verify` 只对受保护 API 做认证：验签、过期、角色、学校；返回 200 + 身份头，或 401 / 403。
- 公开接口由 Nginx 直接绕过 `auth_request`；不再依赖 Spring 的 `permitAll` 穿过网关。
- `/_auth` 带内部 service token；`/internal/**` 由 Nginx `deny all`，公网不可达。
- 接口权限和资源级权限在 Java 过滤器 / service 层完成；Nginx 不解析 JWT。
- 外部请求进入 Nginx 时清掉 `X-Account-*` / `X-Internal-Token`，只信任 `auth_request` / 内部调用写入的值。

### 2.3 WebSocket ticket

- `POST /api/v1/sessions/{session_id}/ws-ticket` 返回：

```json
{
  "ticket": "opaque-short-lived-ticket",
  "expires_at": "2026-11-02T12:00:30Z"
}
```

- ticket 30 秒有效、一次性；绑定 `session_id` + `account_id`。
- 存储：Redis `SETEX ws_ticket:{ticket} 30`，value 为 `{session_id, account_id}`；握手时 `GETDEL`，缺失 / 过期返回 4001。
- WebSocket 握手：`/ws/v1/sessions/{session_id}?ticket=...`。
- 不接受长期 JWT 放 query，避免日志泄露。
- 控制 WS 断线进入 **30 秒 grace**：session 保持 `in_progress`，`SessionActor` 与运行时状态保留；30 秒内可重新申请 ticket 并重连。
- 重连握手：客户端发 `session.resume {last_seq, recording_state}`；`recording_state=active` 才允许恢复，`lost`（页面刷新 / MediaRecorder 丢失）直接 failed。
- 页面刷新、浏览器崩溃、Java 重启不做恢复；只有控制 WS 传输中断且页面仍存活时允许 grace。
- 感知 WS 断线不进入 grace，只影响 ASR / OCR；录制继续，M4 标记 partial。
- 30 秒未重连：写 `session.failed`，清理临时录像；后续 ticket 返回 409 `SESSION_FAILED`。

## 3. 接口权限

- `m1_auth.api_permissions` 存接口目录；`m1_auth.account_type_permissions` 存账号类型映射；seed 只读。
- Spring Security 自定义 `AuthorizationManager` 按 `method + path_pattern` 匹配，默认拒绝；公开接口白名单。
- 资源级权限仍在 service 层：
  - 学生只能看本校、本专业课程；只能操作自己的课程关系。
  - 教师可只读检索本校课程；只有本组课程可管理、查看任务 / 名单 / 统计；任务、提交、报告按课程老师组校验。
  - 管理员只能管理本校元数据；不能创建课程；不能访问 M7 评分 / 统计 / 导出。
  - M5 题库按 `school_id` 隔离。
  - M6 训练结果仅学生本人可见。
- 关键权限码：

| 权限码 | 说明 |
|---|---|
| `admin:school` | 管理本校学校信息 |
| `admin:org` | 专业 / 班级维护与导入 |
| `admin:teacher_majors` | 修改本校教师专业范围 |
| `course:read` | 查看课程 |
| `course:create` | 创建课程（仅教师） |
| `course:manage` | 编辑课程名称 / 结束日期 |
| `course:archive` | 归档 / 恢复课程（仅管理员） |
| `course:join` / `course:leave` | 学生加入 / 退出课程 |
| `course:members:read` | 查看课程老师组 / 学生名单（本组教师 / 本校管理员） |
| `course:members:manage` | 管理课程老师组 / 学生 |
| `task:read` | 查看任务 |
| `task:create` | 创建 / 编辑草稿任务 |
| `task:publish` / `task:close` | 发布 / 关闭任务 |
| `task:assignments:read` | 查看任务分配与提交名单 |
| `session:start` | 开始试讲 / 训练 |
| `session:read` | 查看本人或本课程 session |
| `session:submit` | 提交试讲 |
| `session:replay` | 回放录像 |
| `session:events:read` | 查看课堂事件 |
| `evaluation:read` | 查看 AI 评测报告 |
| `question_bank:read` | 查看题库 |
| `question_bank:manage` | 管理题库 |
| `training:template:read` | 查看训练模板 |
| `training:template:manage` | 发布 / 下架训练模板 |
| `training:session:start` | 开始训练 |
| `training:record:read` | 查看训练记录 |
| `training:record:manage` | 选中 / 删除训练记录 |
| `teacher_eval:config` | 设置任务 AI / 教师权重与打分模式 |
| `teacher_eval:read` | 查看评分名单 / 统计 / 排行榜 |
| `teacher_eval:write` | 录入 / 修改教师评分 |
| `teacher_eval:export` | 导出 CSV |
| `student_result:read` | 学生查看本人最终分 |
| `ws:session` | 建立课堂 WebSocket |

### 3.1 账号类型 → 权限矩阵

| 权限码 | 学生 | 教师 | 管理员 |
|---|---|---|---|
| `admin:school` | — | — | ✓ |
| `admin:org` | — | — | ✓ |
| `admin:teacher_majors` | — | — | ✓ |
| `course:read` | ✓ | ✓ | ✓ |
| `course:create` | — | ✓ | — |
| `course:manage` | — | ✓ | ✓ |
| `course:archive` | — | — | ✓ |
| `course:join` / `course:leave` | ✓ | — | — |
| `course:members:read` | — | ✓（本组） | ✓（本校） |
| `course:members:manage` | — | ✓ | ✓ |
| `task:read` | ✓ | ✓ | — |
| `task:create` | — | ✓ | — |
| `task:publish` / `task:close` | — | ✓ | — |
| `task:assignments:read` | — | ✓ | — |
| `session:start` | ✓ | — | — |
| `session:read` | ✓（本人） | ✓（本课程） | — |
| `session:submit` | ✓ | — | — |
| `session:replay` | ✓（本人） | ✓（本课程） | — |
| `session:events:read` | ✓（本人） | ✓（本课程） | — |
| `evaluation:read` | ✓（本人） | ✓（本课程） | — |
| `question_bank:read` | — | ✓ | ✓ |
| `question_bank:manage` | — | ✓ | ✓ |
| `training:template:read` | ✓ | ✓ | — |
| `training:template:manage` | — | ✓ | — |
| `training:session:start` | ✓ | — | — |
| `training:record:read` / `manage` | ✓ | — | — |
| `teacher_eval:config` | — | ✓ | — |
| `teacher_eval:read` | — | ✓ | — |
| `teacher_eval:write` | — | ✓ | — |
| `teacher_eval:export` | — | ✓ | — |
| `student_result:read` | ✓ | — | — |
| `ws:session` | ✓ | — | — |

> 表中“本人 / 本课程”只是粗粒度说明，具体资源级校验在 service 层完成。管理员一律不能访问 M7 评分、统计、导出。

## 4. 对外 REST API

### 4.1 M1 账号与组织

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/auth/register` | 公开 | 注册；必须确认 `account_type`，注册后不可变更；无类型切换接口 |
| POST | `/api/v1/auth/login` | 公开 | 用户名 + 密码登录，返回 JWT |
| POST | `/api/v1/auth/email/code` | 公开 | 发送邮箱登录验证码；统一 202，不暴露邮箱是否存在 |
| POST | `/api/v1/auth/login/email` | 公开 | 邮箱 + 验证码登录，返回 JWT |
| POST | `/api/v1/auth/email/bind/code` | 登录 | 向待绑定邮箱发送验证码 |
| POST | `/api/v1/auth/email/bind` | 登录 | 校验验证码并绑定 / 换绑邮箱 |
| DELETE | `/api/v1/auth/email` | 登录 | 解绑邮箱（需当前密码） |
| GET | `/api/v1/auth/oauth/wechat/qr` | 公开 | 创建微信扫码 state / 授权 URL，返回二维码内容与过期时间 |
| GET | `/api/v1/auth/oauth/wechat/callback` | 公开（微信回调） | 微信 code + state 回调；服务端换 openid / unionid，不返回 token |
| GET | `/api/v1/auth/oauth/wechat/status` | 公开 | 轮询扫码状态；授权后返回一次性 ticket |
| POST | `/api/v1/auth/oauth/wechat/exchange` | 公开 | login_ticket 换 JWT |
| POST | `/api/v1/auth/oauth/wechat/register` | 公开（registration_ticket） | OAuth 引导注册：创建账号 + 绑定微信 |
| POST | `/api/v1/auth/oauth/wechat/bind` | 登录 | 登录后绑定微信（需扫码 ticket） |
| DELETE | `/api/v1/auth/oauth/wechat` | 登录 | 解绑微信（需当前密码；最后一个登录方式拒绝） |
| GET | `/api/v1/auth/me` | 登录 | 当前账号信息 |
| GET | `/api/v1/public/schools` | 公开 | 注册可选学校 |
| GET | `/api/v1/public/schools/{school_id}/majors` | 公开 | 学校专业 |
| GET | `/api/v1/public/schools/{school_id}/grades` | 公开 | 年级选项 |
| GET | `/api/v1/public/classes` | 公开 | 按学校 / 专业 / 年级查班级 |
| POST | `/api/v1/admin/schools` | `admin:school` | 未绑定管理员创建学校 |
| GET | `/api/v1/admin/school` | `admin:school` | 本校信息 |
| PATCH | `/api/v1/admin/school` | `admin:school` | 修改本校信息 |
| GET / POST | `/api/v1/admin/majors` | `admin:org` | 专业列表 / 新增 |
| PATCH | `/api/v1/admin/majors/{major_id}` | `admin:org` | 编辑 / 停用专业 |
| POST | `/api/v1/admin/majors/import` | `admin:org` | CSV / Excel 导入；整批回滚 |
| GET | `/api/v1/admin/imports/templates/{type}` | `admin:org` | 下载导入模板；`type` 为 `major` 或 `class` |
| GET / POST | `/api/v1/admin/classes` | `admin:org` | 班级列表 / 新增 |
| PATCH | `/api/v1/admin/classes/{class_id}` | `admin:org` | 编辑 / 停用班级 |
| POST | `/api/v1/admin/classes/import` | `admin:org` | CSV / Excel 导入；整批回滚 |
| GET | `/api/v1/admin/teachers` | `admin:teacher_majors` | 本校教师列表（含 `roles`） |
| PUT | `/api/v1/admin/teachers/{account_id}/majors` | `admin:teacher_majors` | 修改教师专业范围 |
| PUT | `/api/v1/admin/teachers/{account_id}/roles` | `admin:roles` | 授予 / 撤销管理员角色（`{"admin":true}`）；不改主身份，需重新登录生效 |

#### 4.1.1 邮箱验证码与微信扫码

```json
POST /api/v1/auth/email/code
{"email":"user@example.com","purpose":"login"}
→ 202 {"accepted":true}

POST /api/v1/auth/login/email
{"email":"user@example.com","code":"123456"}
→ 200 {"token":"...","expires_at":"...","me":{...}}
```

```json
GET /api/v1/auth/oauth/wechat/qr
→ 200 {"state":"...","authorize_url":"https://open.weixin.qq.com/connect/qrconnect?...",
        "expires_at":"..."}

GET /api/v1/auth/oauth/wechat/status?state=...
→ 200 {"status":"pending|scanned|authorized|needs_registration|expired|failed",
        "ticket":"..."}          # authorized 时一次性 login_ticket
                                  # needs_registration 时 registration_ticket

POST /api/v1/auth/oauth/wechat/exchange
{"ticket":"..."} → 200 {"token":"...","expires_at":"...","me":{...}}

POST /api/v1/auth/oauth/wechat/register
{"ticket":"...","account_type":"student","username":"张三","password":"********",
 "school_id":"...","major_id":"...","class_id":"..."} → 201 {"token":"...","me":{...}}
```

- 需登录的绑定 / 解绑接口使用 `auth:self` 权限码（M1 seed）；公开登录 / 扫码 / 回调接口不写入 `api_permissions`，由 Nginx 白名单 + Spring `permitAll` 放行。
- **URL 级权限由 `PermissionAuthorizationManager` 按 `api_permissions` 表判定**（RBAC）：`/api/v1/auth/**` 与 `/api/v1/admin/**` 已接入（M1）；表中查不到规则一律拒绝（fail closed），避免漏配被默认放行。M2—M7 路径暂用「登录即可」，待各自 owner 核对权限种子后再切换。
- 数据级校域（跨校拒绝）不属 RBAC 范畴，仍由服务层 `CROSS_SCHOOL_ACCESS` 校验；两者互补：RBAC 管「能不能调这个接口」，数据权限管「能操作哪些行」。
- 所有失败统一 `UNAUTHORIZED` 或 `VALIDATION_ERROR`，不区分邮箱 / 账号 / 微信身份是否存在。
- 验证码限流：重发 60 秒；邮箱 5 次 / 小时；IP 20 次 / 小时；超限 429 `RATE_LIMITED`。
- 微信 state / ticket 过期、重放、provider 失败：`VALIDATION_ERROR` / `SERVICE_UNAVAILABLE`；不签发 JWT。
- `auth_login_events` 记录 `event_type`（`login` / `bind` / `unbind`）/ method / provider / success / ip / user_agent；响应与日志不含验证码、access token、JWT 以外的敏感值。

### 4.2 M2 课程

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/courses` | `course:read` | 课程列表；按角色过滤 |
| POST | `/api/v1/courses` | `course:create` | 教师创建课程 |
| GET | `/api/v1/courses/{course_id}` | `course:read` | 课程详情 |
| PATCH | `/api/v1/courses/{course_id}` | `course:manage` | 编辑名称 / `end_at` |
| POST | `/api/v1/courses/{course_id}/archive` | `course:archive` | 管理员归档 |
| POST | `/api/v1/courses/{course_id}/restore` | `course:archive` | 管理员恢复 |
| GET | `/api/v1/courses/{course_id}/teachers` | `course:members:read` | 老师组 |
| POST | `/api/v1/courses/{course_id}/teachers` | `course:members:manage` | 添加教师 |
| DELETE | `/api/v1/courses/{course_id}/teachers/{account_id}` | `course:members:manage` | 移出教师 |
| GET | `/api/v1/courses/{course_id}/students` | `course:members:read` | 学生名单；`include_history` 可含退出 / 移出 |
| POST | `/api/v1/courses/{course_id}/students/class` | `course:members:manage` | 按班级批量添加；逐行结果 |
| POST | `/api/v1/courses/{course_id}/students/account` | `course:members:manage` | 按账号 ID 添加；单条 |
| DELETE | `/api/v1/courses/{course_id}/students/{account_id}` | `course:members:manage` | 移出学生 |
| POST | `/api/v1/courses/{course_id}/join` | `course:join` | 学生加入 |
| POST | `/api/v1/courses/{course_id}/leave` | `course:leave` | 学生退出 |

### 4.3 M3 课堂与录制

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/student/tasks` | `task:read` | 学生任务列表 |
| GET | `/api/v1/student/tasks/{task_id}` | `task:read` | 学生任务详情 / 面试前检查；按角色裁剪 |
| GET | `/api/v1/courses/{course_id}/tasks` | `task:read` | 课程任务列表 |
| POST | `/api/v1/courses/{course_id}/tasks` | `task:create` | 创建任务 / 草稿 |
| GET | `/api/v1/tasks/{task_id}` | `task:read` | 任务详情 |
| PATCH | `/api/v1/tasks/{task_id}` | `task:create` | 编辑草稿 |
| POST | `/api/v1/tasks/{task_id}/publish` | `task:publish` | 发布任务 |
| POST | `/api/v1/tasks/{task_id}/close` | `task:close` | 关闭任务 |
| DELETE | `/api/v1/tasks/{task_id}` | `task:create` | 删除草稿 |
| POST | `/api/v1/tasks/{task_id}/material` | `task:create` | 上传 / 替换参考材料（multipart） |
| GET | `/api/v1/tasks/{task_id}/material` | `task:read` | 材料元数据 + 解析文本（教师组） |
| DELETE | `/api/v1/tasks/{task_id}/material` | `task:create` | 删除参考材料 |
| GET | `/api/v1/tasks/{task_id}/assignments` | `task:assignments:read` | 分配与提交名单 |
| POST | `/api/v1/tasks/{task_id}/sessions` | `session:start` | 学生创建试讲 session |
| GET | `/api/v1/sessions/{session_id}` | `session:read` | session 详情 |
| POST | `/api/v1/sessions/{session_id}/start` | `session:start` | 开始录制 / 课堂（幂等兜底） |
| POST | `/api/v1/sessions/{session_id}/end` | `session:start` | 结束课堂 |
| POST | `/api/v1/sessions/{session_id}/cancel` | `session:start` | 取消未开始 / 进行中的任务 session；abort multipart |
| POST | `/api/v1/sessions/{session_id}/submit` | `session:submit` | 提交试讲 |
| GET | `/api/v1/sessions/{session_id}/replay` | `session:replay` | 回放数据 |
| GET | `/api/v1/sessions/{session_id}/events` | `session:events:read` | 事件历史 |
| GET | `/api/v1/sessions/{session_id}/texts` | `session:read` | 统一文本流（教师 / 学生 / 考官），按时间 / 说话人过滤 |
| GET | `/api/v1/sessions/{session_id}/recordings` | `session:read` | 录像列表 |
| POST | `/api/v1/sessions/{session_id}/recordings/{stream_type}/multipart/start` | `session:start` | 创建 MinIO multipart，返回 `upload_id` / `part_size` / `object_key` |
| POST | `/api/v1/sessions/{session_id}/recordings/{stream_type}/multipart/part-url` | `session:start` | 获取下一段 part 预签名 PUT URL |
| POST | `/api/v1/sessions/{session_id}/recordings/{stream_type}/multipart/complete` | `session:start` | 提交 parts、duration、size、mime、offset_ms（可选，缺省 0） |
| POST | `/api/v1/sessions/{session_id}/recordings/{stream_type}/multipart/abort` | `session:start` | 中止 multipart，清理分片 |
| POST | `/api/v1/sessions/{session_id}/ws-ticket` | `ws:session` | WebSocket ticket |
| POST | `/api/v1/sessions/{session_id}/perception/screen` | `session:start` | 上传屏幕截图（JPEG + `at_ms` + `change_score`）；202，Java 异步 OCR |

- `stream_type`：`camera` / `screen` / `audio`。
- 分片：MediaRecorder timeslice 5 秒，缓冲至 5 MB 上传；第一段保留，补 WebM 时长后重传 part 1 再 complete。
- `complete` 的 `offset_ms`：该路录制开始相对本轮 t0（最早启动的录制流）的毫秒偏移，非负，缺省 0；服务端写入 `sessions.stream_offsets` 与 `recording_streams.offset_ms`，回放 manifest 原样返回。
- 提交按模式校验 `required_streams`：自由 / 情景 camera + screen + audio；结构化面试 camera + audio；缺任一必需流返回 `RECORDING_INCOMPLETE` 并列出缺失流。
- 回放只支持 Chrome / Edge 的 WebM。
- 感知通道：`WS /ws/v1/sessions/{session_id}/perception?ticket=...` 只传二进制音频；控制 WS 只传命令 / 事件。
- 结构化面试复用同一套 session / WS，区别在任务 `mode=interview` 与题目配置。
- 训练 session 复用 `/api/v1/training/sessions`，不进入学生任务列表。
- **学生可见性**：学生任务 / session / WS 快照不得返回 `reference_material`、`answer_points`、`rubric_weights`、虚拟学生参数与个性文本；只返回课题、模式、时长、虚拟学生姓名 / 头像 / 状态。教师组任务详情可返回完整配置。
- **开始 / 结束**：课堂进行中以 WS 命令 `session.start` / `teacher.end_session` 为主；REST `/start` / `/end` 只做服务端幂等兜底，不作为页面刷新恢复。页面刷新按 recording_state=lost 处理，session failed。

### 4.4 M4 评测

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/submissions/{submission_id}/evaluation` | `evaluation:read` | 按提交件查评测 |
| GET | `/api/v1/evaluations/{evaluation_id}` | `evaluation:read` | 评测详情 / 状态 |
| GET | `/api/v1/evaluations/{evaluation_id}/report` | `evaluation:read` | 报告：总分、维度、雷达、建议 |
| GET | `/api/v1/evaluations/{evaluation_id}/issues` | `evaluation:read` | 问题定位与建议 |
| GET | `/api/v1/training/records/{record_id}/evaluation` | `evaluation:read` | 训练评测报告，仅本人 |

- 不提供手动重跑；失败由系统自动重试。
- `partial` 报告展示已完成维度，不展示总分。
- `failed` 请求 `/report` 返回 422 `EVALUATION_FAILED`；失败原因从 `/evaluations/{id}` 查询。

### 4.5 M5 题库

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/question-bank/categories` | `question_bank:read` | 类别列表 |
| POST | `/api/v1/question-bank/categories` | `question_bank:manage` | 新增类别 |
| PATCH | `/api/v1/question-bank/categories/{category_id}` | `question_bank:manage` | 编辑 / 停用类别 |
| GET | `/api/v1/question-bank/questions` | `question_bank:read` | 题目列表 / 筛选 / 分页 |
| POST | `/api/v1/question-bank/questions` | `question_bank:manage` | 新增题目 |
| PATCH | `/api/v1/question-bank/questions/{question_id}` | `question_bank:manage` | 编辑 / 停用题目 |
| POST | `/api/v1/admin/question-bank/demo-init` | `question_bank:manage` | 仅 demo profile：初始化本校演示题库 |

- 题目字段仅 `stem`、`answer_points`、`category_id`、`status`。
- M3 结构化面试选题复用 `GET /question-bank/questions`；发布时快照。

### 4.6 M6 训练

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET / POST | `/api/v1/training/templates` | `training:template:read` / `manage` | 教师模板列表 / 发布 |
| GET | `/api/v1/training/templates/{template_id}` | `training:template:read` | 模板详情；教师仅自己创建，学生仅目标课程可用模板 |
| POST | `/api/v1/training/templates/{template_id}/unpublish` | `training:template:manage` | 下架模板 |
| GET | `/api/v1/student/training/templates` | `training:template:read` | 学生可选模板 |
| GET | `/api/v1/student/training/templates/{template_id}` | `training:template:read` | 学生模板公开配置 |
| POST | `/api/v1/training/materials` | `training:session:start` | 上传自由训练参考材料 |
| DELETE | `/api/v1/training/materials/{material_id}` | `training:session:start` | 删除未使用材料 |
| POST | `/api/v1/training/sessions` | `training:session:start` | 开始训练 |
| GET | `/api/v1/training/sessions/{session_id}` | `training:session:start` | 训练 session |
| POST | `/api/v1/training/sessions/{session_id}/end` | `training:session:start` | 结束训练 |
| POST | `/api/v1/training/sessions/{session_id}/cancel` | `training:session:start` | 取消未开始训练 |
| POST | `/api/v1/training/records` | `training:record:manage` | 选中一次训练生成评测 |
| GET | `/api/v1/training/records` | `training:record:read` | 本人训练记录 |
| GET | `/api/v1/training/records/{record_id}` | `training:record:read` | 本人记录详情 |
| DELETE | `/api/v1/training/records/{record_id}` | `training:record:manage` | 删除训练记录 |

### 4.7 M7 教师评测

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/tasks/{task_id}/grading-config` | `teacher_eval:config` | 任务 AI / 教师权重与模式 |
| PUT | `/api/v1/tasks/{task_id}/grading-config` | `teacher_eval:config` | 设置权重 / 模式 |
| DELETE | `/api/v1/tasks/{task_id}/teacher-scores` | `teacher_eval:config` | 清空任务全部教师评分并解锁 |
| GET | `/api/v1/tasks/{task_id}/grading-submissions` | `teacher_eval:read` | 评分名单与状态 |
| PUT | `/api/v1/submissions/{submission_id}/teacher-score` | `teacher_eval:write` | 单条教师评分 / 评语 |
| POST | `/api/v1/tasks/{task_id}/teacher-scores/batch` | `teacher_eval:write` | 批量保存 |
| GET | `/api/v1/tasks/{task_id}/stats` | `teacher_eval:read` | 任务 / 班级统计、薄弱项 |
| GET | `/api/v1/tasks/{task_id}/leaderboard` | `teacher_eval:read` | 排行榜 |
| GET | `/api/v1/courses/{course_id}/stats` | `teacher_eval:read` | 课程统计 |
| GET | `/api/v1/tasks/{task_id}/export` | `teacher_eval:export` | CSV 导出 |
| GET | `/api/v1/student/results` | `student_result:read` | 学生本人成绩列表 |
| GET | `/api/v1/submissions/{submission_id}/result` | `student_result:read` / `teacher_eval:read` | 学生本人 / 老师组最终分 / 评语 |

- 管理员没有 `teacher_eval:*` 权限，访问返回 403。
- M7 统计只使用 `completed` 最终分；`pending_*` / `ai_partial` / `ai_failed` 单独计数。

## 5. 内部 API

### 5.1 Java 内部接口

| 方法 | 路径 | 调用方 | 说明 |
|---|---|---|---|
| GET / POST | `/internal/auth/verify` | Nginx | 验 JWT，返回身份头 |
| POST | `/internal/evaluations/{evaluation_id}/start` | Python worker | 标记评测开始；幂等 |
| GET | `/internal/evaluations/{evaluation_id}/input/manifest` | Python worker | 配置、session、录像 URL、counts、flags |
| GET | `/internal/evaluations/{evaluation_id}/input/texts` | Python worker | 统一文本分页 |
| GET | `/internal/evaluations/{evaluation_id}/input/frames` | Python worker | 帧分页 |
| GET | `/internal/evaluations/{evaluation_id}/input/events` | Python worker | 事件分页 |
| POST | `/internal/evaluations/{evaluation_id}/result` | Python worker | 回传评测结果；幂等 |
| POST | `/internal/evaluations/{evaluation_id}/fail` | Python worker | 回传失败 / partial |

- 内部接口只接受内网 / service token；不经过 Nginx 公网路由。
- `result` 请求体：

```json
{
  "job_id": "uuid",
  "attempt": 1,
  "status": "completed",
  "model_version": "deepseek-flash",
  "prompt_version": "m4-eval-v1",
  "rubric_version": "m4-rubric-v1",
  "dimensions": [
    {"dimension": "content", "score": 82.5, "sub_scores": {}, "confidence": 0.86}
  ],
  "issues": [
    {"dimension": "content", "start_ms": 12000, "end_ms": 18000, "severity": "medium", "evidence": "...", "suggestion": "..."}
  ],
  "partial_reasons": [],
  "input_flags": {},
  "token_usage": {"prompt": 12000, "completion": 1800}
}
```

- `attempt` 与 `job_id` 一起用于幂等；Java 只接受当前 attempt，旧 attempt 忽略。
- 输入分页接口按 `after_id` / `after_ts` / `after_seq` 游标，默认 500 / 200 / 500 条。

### 5.2 Python AI 内部接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/v1/llm/chat` | 文本对话 / 流式 |
| POST | `/internal/v1/llm/json` | 结构化 JSON 输出 |
| POST | `/internal/v1/ocr` | 图片 OCR，返回文本 / 框 / 置信度 |
| POST | `/internal/v1/vision/analyze` | 关键帧视觉分析 |
| WS | `/internal/v1/asr/stream` | 流式 ASR |
| POST | `/internal/v1/classroom/question` | 生成虚拟学生提问 |
| POST | `/internal/v1/classroom/answer` | 生成学生回答 |
| POST | `/internal/v1/classroom/classify` | 教师话语分类 |

- Python 服务无状态；请求自带上下文；响应返回 `model_version` / `prompt_version`。
- `classroom/question` / `answer` 返回 `{text, context_sufficient, input_question_complete, output_complete, missing, confidence, model_version, prompt_version}`；Java 根据 flags 扩窗重试（2 → 4 → 6 分钟，最多 3 次）。
- 认证：`X-Service-Token` 或 mTLS；只在内网暴露。
- 统一错误格式与 REST 相同。

## 6. WebSocket 协议

### 6.1 连接

```text
WS /ws/v1/sessions/{session_id}?ticket=...             # 控制通道：命令 / 事件
WS /ws/v1/sessions/{session_id}/perception?ticket=...  # 感知通道：二进制 PCM 音频
```

- 仅 session 所属学生可连接；`session_type` 为 `task` 或 `training`。
- 握手校验 ticket + session + account；失败关闭 4001。
- 控制通道连接后服务端发送 `session.snapshot`，包含 `last_seq`、当前状态、面试当前题 / `answer_id` / 剩余时间、虚拟学生当前状态、录制状态。
- 控制通道断线进入 30 秒 grace；session 保持 `in_progress`，`SessionActor` 内存状态保留；30 秒内可重新申请 ticket 重连。
- 重连后客户端发 `session.resume {last_seq, recording_state}`；`recording_state=active` 才允许恢复；服务端回 `session.resumed` 并补发 `last_seq` 之后的事件。
- 页面刷新 / 浏览器崩溃 / `recording_state=lost` / 30 秒超时 / Java 重启：session `failed`，不做恢复。
- 感知通道首条 JSON：`{type:"audio.start", format:"pcm16", sample_rate:16000, channels:1}`；随后二进制音频帧；`audio.stop` 结束；不使用事件信封。
- 感知通道失败只影响 ASR / OCR，录制继续（M4 partial）；感知 WS 可独立重连。

### 6.2 信封

服务端事件：

```json
{
  "v": 1,
  "type": "student.hand_raised",
  "event_id": "uuid",
  "seq": 42,
  "at_ms": 12345,
  "session_id": "uuid",
  "payload": {}
}
```

客户端命令：

```json
{
  "v": 1,
  "type": "teacher.call_on",
  "command_id": "uuid",
  "payload": {"student_id": "uuid", "hand_raise_id": "uuid"}
}
```

- `seq` 按 session 单调递增；初始连接由 `session.snapshot.last_seq` + 后续事件保证连续；重连时用 `session.resume.last_seq` 补发，补不齐才失败。
- `session.time_tick` / `ping` 不回放；快照已包含当前计时。
- `at_ms` 是 session 相对毫秒；客户端只用它渲染，不用本地计时器判断。
- `command_id` 用于幂等；服务端回 `server.ack`。
- 消息大小限制 64 KB；超限关闭 4005。

### 6.3 客户端命令

| 命令 | payload | 说明 |
|---|---|---|
| `recording.ready` | `{streams:[{stream_type,status}]}` | 客户端录制器就绪；服务端确认必需流 |
| `session.start` | `{}` | 开始课堂；前置 `recording.ready` |
| `session.resume` | `{last_seq, recording_state}` | 30 秒 grace 内重连；`recording_state=active/lost` |
| `teacher.ask_class` | `{}` | 向全班提问，触发举手窗口；intent=answer |
| `teacher.ask_student_question` | `{}` | 让学生提问，触发举手窗口；intent=student_question |
| `teacher.call_on` | `{student_id, hand_raise_id}` | 点名 |
| `teacher.mark_answer` | `{answer_id, correctness}` | 对 / 错 / 不完整 |
| `teacher.manage_student` | `{student_id, action}` | 提醒 / 管理纪律 |
| `teacher.end_dialogue` | `{dialogue_id}` | 结束对话并结束问答 |
| `teacher.end_qa` | `{}` | 结束问答，回到讲解 |
| `teacher.end_session` | `{}` | 结束试讲 |
| `interview.next_question` | `{}` | 下一题 |
| `interview.end` | `{}` | 结束面试 |
| `time.sync` | `{client_time_ms}` | 时钟同步 |
| `pong` | `{client_time_ms}` | 心跳响应 |

- `correctness`：`correct` / `partial` / `wrong`。
- `action`：`remind` 等；后续扩展。
- 非法状态下的命令返回 `error`，不改变状态。

### 6.4 服务端事件

| 事件 | payload | 说明 |
|---|---|---|
| `session.snapshot` | `{state, mode, public_config, virtual_students, interview, recording, last_seq, elapsed_ms}` | 连接 / 重连的初始状态；`interview` 只含当前题、题号、`answer_id`、剩余时间；不含参考材料 / 答案要点 / 虚拟学生参数 |
| `session.started` | `{started_at_ms}` | 课堂开始（t0） |
| `session.time_tick` | `{elapsed_ms, remaining_ms?, discipline_level, states}` | 每秒 tick；不落库回放 |
| `session.ended` | `{reason, duration_ms}` | 课堂结束 |
| `session.failed` | `{reason, error_code?}` | 30 秒 grace 超时 / 录制丢失 / 技术失败 |
| `session.resumed` | `{last_seq, server_time_ms}` | 重连成功；随后补发缺失事件 |
| `recording.ready_ack` | `{required_streams, ok}` | 服务端确认录制器就绪，允许 session.start |
| `session.state` | `{state}` | session 状态变化 |
| `classroom.state_changed` | `{from, to, qa_phase, trigger}` | 讲解 / 问答 / 结束切换 |
| `classroom.question_asked` | `{intent, source}` | 老师提问 / 让学生提问 |
| `classroom.question_suggested` | `{confidence}` | 语音低置信，等待教师确认 |
| `classroom.hand_window_timeout` | `{hand_raise_ids}` | 30 秒未点名 |
| `classroom.qa_ended` | `{reason}` | 问答结束 |
| `classroom.discipline_level_changed` | `{from, to, distracted_count, whispering_count, noisy_ratio, avg_attention, duration_ms, reason}` | 课堂纪律聚合；record-only |
| `student.state_changed` | `{student_id, from, to, reason}` | 单个学生行为状态变化 |
| `student.hand_raised` | `{student_id, hand_raise_id, question_preview?}` | 举手；`question_preview` 只作 UI，权威文本用 `text_id` |
| `student.hand_lowered` | `{student_id, hand_raise_id, reason}` | 放下手 |
| `student.question` | `{student_id, text_id, question_id}` | 学生提问；全文在 `session_texts` |
| `student.answer` | `{student_id, answer_id, text_id, quality}` | 学生回答；全文在 `session_texts` |
| `student.follow_up` | `{student_id, answer_id, text_id}` | 追问；全文在 `session_texts` |
| `teacher.called_on` | `{student_id, hand_raise_id?}` | 点名 |
| `teacher.marked` | `{answer_id, correctness}` | 对 / 错 / 不完整 |
| `teacher.management_action` | `{student_id, action}` | 提醒 / 管理 |
| `dialogue.started` / `dialogue.ended` | `{dialogue_id, student_id, reason?}` | 对话 |
| `discipline.event` | `{level, student_ids, event_type}` | 走神 / 说话 / 吵闹；与 05A 对齐 |
| `interview.question_shown` | `{question_id, order, total, text}` | 面试题目 |
| `interview.answer_started` / `interview.answer_ended` | `{question_id, answer_id}` | 回答时间线 |
| `interview.next_question` | `{question_id, order}` | 下一题 |
| `interview.ended` | `{}` | 面试结束 |
| `submission.created` | `{submission_id}` | 提交成功（M3 任务） |
| `perception.screen_changed` | `{frame_id, at_ms, frame_type}` | 屏幕 OCR / 变化 |
| `perception.acoustic` | `{at_ms, event, value}` | 音量 / 停顿 / 语速异常 |
| `recording.status` | `{stream_type, status}` | 录制状态（由录制模块产生） |
| `server.ack` | `{command_id, ok}` | 命令确认 |
| `error` | `{code, message, command_id}` | 错误 |
| `ping` | `{server_time_ms}` | 心跳 |

### 6.5 心跳、时钟与关闭

- 服务端每 15 秒发 `ping`；客户端回 `pong`；30 秒无响应关闭 4004。
- 客户端可发 `time.sync`；服务端回 `at_ms` / `server_time_ms`，客户端计算偏移。
- 关闭码：
  - 4001：认证 / ticket 无效
  - 4002：session 已失败或不可用
  - 4003：状态不允许
  - 4004：心跳超时
  - 4005：消息过大 / 消费过慢
- 控制 WS 关闭：
  - `in_progress`：进入 30 秒 grace，不立即 failed；超时未重连才 failed。
  - `preparing`：30 秒内可重连；超时取消 / failed。
  - `ended / reviewing / submitted`：正常关闭，不改变状态。
- 客户端在 grace 内显示“连接中断，30 秒内重连”；页面刷新 / 录制器丢失按 failed 处理。
- 30 秒内重连成功：`session.resumed` + 补发事件；超过 30 秒：关闭 4002，session failed。

## 7. Redis Streams 协议

### 7.1 评测任务

- Stream：`evaluation:jobs`；consumer group：`eval-workers`。
- 消息字段：

```json
{
  "job_id": "uuid",
  "evaluation_id": "uuid",
  "source_type": "task_submission",
  "source_id": "uuid",
  "session_id": "uuid",
  "attempt": 1
}
```

- 消息不携带全文；worker 通过 `/internal/evaluations/{id}/input/*` 拉取配置、录像 URL、文本 / 帧 / 事件分页。
- Java 创建 `evaluations` + `evaluation_jobs` 后 `XADD`；`XADD` 失败时任务保持 `queued`，由调度器重投。
- Python worker `XREADGROUP` → 处理 → 回调 Java → `XACK`。
- 回调失败不 `XACK`，留在 pending；超过最大尝试次数写入 `evaluation:dead`，Java 标记 `failed`。
- Stream 设置 `MAXLEN ~ 10000`；consumer 名 `worker-{hostname}-{pid}`。

### 7.2 结果回调

```http
POST /internal/evaluations/{evaluation_id}/result
X-Service-Token: ...
Idempotency-Key: {job_id}
```

- Java 按 `evaluation_id` 幂等 upsert；重复回调不重复计分。
- `status`：`completed` / `partial` / `failed`。
- Python 不保存评测结果；结果只存在 Java PostgreSQL。

## 8. 错误、分页与幂等

### 8.1 错误格式

```json
{
  "code": "COURSE_ARCHIVED",
  "message": "课程已归档",
  "details": {"course_id": "uuid"},
  "trace_id": "uuid"
}
```

### 8.2 HTTP 状态码

| 状态码 | 场景 |
|---|---|
| 400 | 参数校验失败 |
| 401 | 未认证 / token 过期 |
| 403 | 无权限 / 跨校 / 角色不符 |
| 404 | 资源不存在 |
| 409 | 版本冲突 / 幂等冲突 / 状态冲突 |
| 422 | 业务规则不允许 |
| 429 | 限流 |
| 500 | 内部错误 |
| 502 | AI / 上游返回非法结果 |
| 503 | AI / 题库 / 依赖服务不可用 |

### 8.2.1 首批错误码

| code | HTTP | 说明 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 参数校验失败 |
| `UNAUTHORIZED` | 401 | 未认证 |
| `TOKEN_EXPIRED` | 401 | token 过期 |
| `FORBIDDEN` | 403 | 无权限 |
| `CROSS_SCHOOL_ACCESS` | 403 | 跨校访问 |
| `ROLE_NOT_ALLOWED` | 403 | 角色不允许 |
| `RESOURCE_NOT_FOUND` | 404 | 资源不存在 |
| `VERSION_CONFLICT` | 409 | 乐观锁冲突 |
| `DUPLICATE_RESOURCE` | 409 | 重复创建 |
| `IDEMPOTENCY_CONFLICT` | 409 | 幂等键冲突 |
| `RATE_LIMITED` | 429 | 限流 |
| `INTERNAL_ERROR` | 500 | 内部错误 |
| `SERVICE_UNAVAILABLE` | 503 | 依赖服务不可用 |
| `USERNAME_TAKEN` | 409 | 用户名已占用 |
| `EMAIL_TAKEN` | 409 | 邮箱已绑定其他账号（统一文案，不泄露账号） |
| `OAUTH_IDENTITY_TAKEN` | 409 | 微信身份已绑定其他账号 |
| `OAUTH_STATE_INVALID` | 400 | state / ticket 过期、重放或已使用 |
| `OAUTH_PROVIDER_UNAVAILABLE` | 503 | 微信 / 邮件服务不可用 |
| `SCHOOL_ALREADY_BOUND` | 409 | 管理员已绑定学校 |
| `CLASS_NOT_MATCHED` | 422 | 无匹配班级 |
| `CLASS_DISABLED` | 422 | 班级已停用，不能注册 / 加入 |
| `MAJOR_DISABLED` | 422 | 专业已停用 |
| `LAST_LOGIN_METHOD` | 422 | 不能解绑最后一个可用登录方式 |
| `COURSE_ARCHIVED` | 422 | 课程已归档 |
| `COURSE_MAJOR_MISMATCH` | 422 | 课程专业范围不符 |
| `COURSE_NAME_TAKEN` | 409 | 课程名称在本校已存在 |
| `ALREADY_JOINED` | 409 | 已加入课程 |
| `TEACHER_NOT_IN_GROUP` | 403 | 不在课程老师组 |
| `LAST_TEACHER` | 422 | 不能移出最后一名教师 |
| `CLASS_NOT_ELIGIBLE` | 422 | 班级不在课程专业范围 |
| `TASK_NOT_DRAFT` | 422 | 任务不是草稿 |
| `TASK_ALREADY_PUBLISHED` | 409 | 任务已发布 |
| `TASK_CLOSED` | 422 | 任务已关闭 |
| `TASK_NO_STUDENTS` | 422 | 课程无在课学生 |
| `COURSE_ENDED` | 422 | 课程已结束，不能新建 / 发布任务 |
| `INVALID_END_AT` | 422 | 结束时间早于当前时间或格式非法 |
| `TASK_CONFIG_INVALID` | 422 | 任务配置不完整或字段非法 |
| `REFERENCE_MATERIAL_MISSING` | 422 | 参考材料缺失 |
| `MATERIAL_NOT_APPLICABLE` | 422 | 结构化面试不需要参考材料 |
| `MATERIAL_PARSE_FAILED` | 422 | 材料解析失败 |
| `MATERIAL_TOO_LARGE` | 422 | 材料超过 20 MB 或解析文本超限 |
| `MATERIAL_TYPE_UNSUPPORTED` | 422 | 材料格式不支持 |
| `WEIGHT_SUM_INVALID` | 422 | 权重和不为 100% |
| `QUESTION_COUNT_INVALID` | 422 | 面试题目数量不在 1—10 |
| `QUESTION_DUPLICATED` | 422 | 面试题目重复 |
| `INTERVIEW_NO_MORE_QUESTIONS` | 422 | 已是最后一题 |
| `SESSION_ACTIVE_EXISTS` | 409 | 已有进行中的面试 session |
| `SESSION_STATE_INVALID` | 409 | session 状态不允许 |
| `SESSION_FAILED` | 409 | session 已失败，不能重连 / 继续 |
| `RECORDING_INCOMPLETE` | 422 | 必需录制流不完整（按模式） |
| `WS_TICKET_INVALID` | 401 | WS ticket 无效 |
| `WS_SESSION_FORBIDDEN` | 403 | 无权连接该 session |
| `EVALUATION_NOT_READY` | 409 | 评测未完成 |
| `EVALUATION_FAILED` | 422 | 评测失败 |
| `EVALUATION_INPUT_INCOMPLETE` | 422 | 输入缺失，无法评测 |
| `EVALUATION_TIMEOUT` | 503 | 评测超时 |
| `EVALUATION_INVALID_RESULT` | 502 | worker 回调结果非法 |
| `EVALUATION_ATTEMPT_STALE` | 409 | 过期 attempt 回调 |
| `AI_SERVICE_UNAVAILABLE` | 503 | AI 服务不可用 |
| `AI_TIMEOUT` | 503 | AI 调用超时 |
| `AI_OUTPUT_INVALID` | 502 | AI 返回格式错误 |
| `QUESTION_BANK_EMPTY` | 422 | 题库为空 |
| `QUESTION_DISABLED` | 422 | 题目已停用 |
| `ANSWER_POINTS_MISSING` | 422 | 答案要点缺失 |
| `QUESTION_IN_USE` | 409 | 题目被任务引用 |
| `TRAINING_SOURCE_INVALID` | 422 | 源任务不是已发布 / 已关闭自由试讲 |
| `TRAINING_NOT_ELIGIBLE` | 422 | 教师 / 学生 / 课程不满足训练条件 |
| `TRAINING_TEMPLATE_EXISTS` | 409 | 同源任务 / 课程 / 档位已有模板 |
| `TRAINING_TEMPLATE_UNAVAILABLE` | 422 | 模板已下架 / 不可用 |
| `TRAINING_SESSION_ACTIVE` | 409 | 已有进行中的训练 session |
| `TRAINING_CONFIG_IMMUTABLE` | 422 | 试图修改时长 / 权重 |
| `TRAINING_MATERIAL_INVALID` | 422 | 训练参考材料不可用 |
| `TRAINING_RECORD_LIMIT` | 422 | 超过 3 条训练记录 |
| `TRAINING_RECORD_EXPIRED` | 404 | 训练记录已过期 / 删除；对外统一 404 |
| `GRADING_CONFIG_INVALID` | 422 | 权重 / 打分模式非法 |
| `GRADING_CONFIG_LOCKED` | 409 | 首次评分后权重 / 模式已锁定 |
| `GRADING_CONFIG_VERSION_CONFLICT` | 409 | 评分配置版本冲突 |
| `TEACHER_SCORE_INVALID` | 422 | 分数 / 维度 / 评语非法 |
| `TEACHER_SCORE_CONFLICT` | 409 | 教师评分版本冲突 |
| `SUBMISSION_NOT_SUBMITTED` | 422 | 提交件不存在或未提交 |
| `AI_SCORE_NOT_READY` | 409 | AI 分未完成，无法计算最终分 |
| `DATA_INCONSISTENT` | 422 | M3 / M4 数据缺失或 ID 不一致 |
| `EXPORT_TOO_LARGE` | 422 | 导出超过 10000 行 |

### 8.3 分页

- 业务列表：Query `page`（1 起）、`size`（默认 20，最大 100）、`sort`。
- 课堂事件：`after_seq` + `limit`（默认 100，最大 500），按 `seq` 升序；不使用 page/size。
- 文本 / 帧：按 session + 时间范围查询，不做通用分页。
- 业务列表响应：

```json
{"items": [], "total": 0, "page": 1, "size": 20}
```

### 8.4 幂等与并发

- `Idempotency-Key`：创建 / 提交类接口；服务端按 key 缓存结果 24 小时。
- 业务唯一键兜底：`submission_id`、`source_type + source_id`、`submission_id` 教师评分。
- 乐观锁：M7 教师评分 `version`，冲突 409；M5 编辑可选 `version`（P1）。
- 批量保存按行返回结果，允许部分成功。

## 9. Nginx 配置要点

- `/api/` → Java；`/ws/` → Java；Vue 静态资源由 Nginx 托管。
- `auth_request` 只做认证；权限在 Java。
- WebSocket：

```nginx
location /ws/ {
    # 不做 auth_request；Java 握手 GETDEL 校验 ?ticket=
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;
    proxy_buffering off;
    proxy_pass http://java_app;
}
```

- `client_max_body_size`：材料上传路由 25 MB，屏幕截图路由 5 MB；录像走 MinIO 预签名直传，Nginx 不代理大文件。
- MinIO：独立 HTTPS 子域，预签名 URL 指向公网 HTTPS host；配置 CORS；Nginx 不代理大文件。
- 限流：登录、注册、邮箱验证码、微信二维码、AI 触发接口分别 `limit_req`；登录 / 注册默认 5 次 / 分钟，burst 5；邮箱验证码 60 秒重发、邮箱 5 次 / 小时、IP 20 次 / 小时；微信二维码按 IP 限流。
- 公开数据接口（`/api/v1/public/**`）单独一个 `limit_req` zone：**30 次 / 分钟 / IP，burst 20**；命中限流返回 **429**（`limit_req_status 429`，Nginx 默认的 503 语义不对），响应体为与后端一致的 JSON `{"code":"RATE_LIMITED",...}`（Nginx 默认 HTML 错误页前端无法解析）。这些接口本身免认证，限流是防止组织数据被脚本批量镜像的唯一手段；响应同时带 `Cache-Control: public, max-age=60`，削减正常用户的重复请求。
- **限流只存在于网关层**：`docker-compose.yml` 的 `nginx` 服务在 `gateway` profile 下才启动。直连应用端口（本地 `bootRun` 或未启网关的内网访问）没有任何限流，且 dev profile 还接受 `X-Account-*` 请求头身份——**生产必须只暴露网关，不映射应用端口**。本地验证限流需 `docker compose --profile gateway up -d nginx`。
- 清掉外部 `X-Account-*` / `X-Internal-Token`；`/internal/` 公网 `deny all`。

## 10. OpenAPI 与契约测试

- Java 使用 springdoc 生成 `/v3/api-docs`；按模块分组。
- Python 内部 API 生成 OpenAPI，Java 侧用契约测试校验请求 / 响应。
- 权限覆盖：
  - CI 必须通过“路由 → `api_permissions`”覆盖率检查，缺失则构建失败；
  - 启动时扫描并记录 `ERROR` 日志，但不阻止启动；
  - 默认拒绝，漏配即 403；公开接口维护白名单。
- WebSocket 事件表与 M3 必需事件对齐；增加事件必须同步文档与测试。
- Redis Stream 字段版本化：`payload_version`，Python 与 Java 兼容旧版本。

## 11. 开放问题

| 编号 | 事项 | 当前处理 |
|---|---|---|
| Q1 | JSON 字段命名 | 已确认：全局 `snake_case`；Java Jackson 全局配置；前端类型一致 |
| Q2 | 分页方式 | 已确认：业务列表 `page/size`；事件 `after_seq + limit`；帧 / 文本按时间范围 |
| Q3 | WS ticket 存储 | 已确认：Redis `SETEX` + `GETDEL`，30 秒 TTL，一次性 |
| Q4 | MinIO 访问方式 | 已确认：预签名 URL 直连 MinIO；独立端口 / 子域 + CORS；Nginx 不代理大文件 |
| Q5 | 训练 WS | 已确认：复用 `/ws/v1/sessions/{id}`；`session_type=training`；按类型分支终态与权限 |
| Q6 | 权限表路径匹配 | 已确认：Spring `PathPattern`；CI 强制覆盖率、启动告警 |
| Q7 | 错误码清单 | 已确认：按本文件首批错误码；后续按模块补充，格式不变 |
