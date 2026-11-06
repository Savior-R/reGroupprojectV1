# 2026-11-04 M3 前端课堂 / 面试 / 回看 / 回放切片证据

> 分支：`feat/m3a-task-config`
> 计划：`docs/superpowers/plans/2026-11-04-m3-frontend-classroom-replay.md`（Task 1–8）
> 执行人：M3 Owner（当前使用者，经用户确认）
> 环境：Linux / WSL2，Node v22.22.1、npm 9.2.0、Vite 8.2.2、Vue 3.5.41、Vitest 5.1.0；本机无 Docker CLI / Docker Desktop / Java，无 Linux 浏览器与 Playwright 浏览器缓存；Windows Chrome 可经 WSL interop 以 headless 方式访问 WSL 内 dev server。
> 结论：Task 1–8 的前端切片与最终审查修复已实现，全量 Vitest **15 files / 134 tests 全部通过**，生产构建（`vue-tsc -b && vite build`）通过；**浏览器手工 smoke 被环境阻塞**，未执行完整录制 / 回看 / 提交流程，不声称通过。G2 设计冻结未关闭，G3—G8 均未标记，模块完成度保持 10%。

## 1. 范围与授权

### 1.1 授权

用户在 2026-11-04 选择按前端切片继续全栈实现；2026-11-06 再次确认按计划顺序执行。记录见 `.superpowers/sdd/2026-11-04-m3-frontend-classroom-replay/progress.md`：

> Authorization: the plan's Global Constraints record the user's 2026-11-04 full-stack choice for the shared frontend paths; the user re-confirmed on 2026-11-06 ("按这个顺序继续执行"). The M3A/M3B views are M3 Owner; M3C recording/replay views are Media/Training Owner, covered by the earlier M3C implementation authorization; the shared layer is Score/QA Owner, covered by the plan's stated approval.

按 `docs/team/01` §2.3 / §3：`views/task/**`、`views/classroom/**` 属 M3A/M3B（M3 Owner）；`views/replay/**`、录制 / 回放组件属 M3C（Media/Training Owner，已有 M3C 切片授权覆盖）；`app/router.ts`、`layouts/**`、`stores/auth.ts`、`api/client.ts` 属前端共享层（Score/QA Owner，由计划记录的用户授权覆盖）。本次 Task 8 只改 `docs/team/evidence/**`、`docs/team/02-进度看板.md`、`frontend/README.md`、`server/README.md`，未改任何设计文档。

### 1.2 本次范围

- 前端工具链与共享外壳：Vue Router、Pinia、Element Plus、Axios、`fix-webm-duration`、Vitest/jsdom。
- 学生任务入口：任务列表 / 详情、创建或恢复 session、按 mode 路由到课堂 / 面试。
- 课堂控制 WS 客户端与课堂 reducer：一次性 ticket、心跳、`seq` 去重、30 秒 grace 重连与 `session.resume`。
- 录制采集与分片上传：摄像头 / 屏幕 / 麦克风三路 MediaRecorder、5 秒 timeslice、5 MB 分片、首片补时长后重传、全部分片完成后 complete。
- 课堂页与面试页：录制状态、虚拟学生、提问 / 点名 / 回答 / 纪律、结束流程；面试为摄像头 + 麦克风两路。
- 回看 / 回放：录制完成后轮询、提交、多流同步播放、音频主时钟、事件跳转、`?t=` 初始位置、预签名过期刷新。
- 全量单测、生产构建、进度看板与证据。

不做：Playwright E2E、完整 M1 登录 / JWT、M2 课程与 M3A 管理页 UI、M6 训练页、M4/M7 报告与评分页、感知音频 WS / OCR、留存期定时任务。

### 1.3 跨模块与配置说明

- `docker-compose.yml` 未修改：M3C 计划 Task 3 已配置 MinIO CORS 与公网端点，本切片直接复用。已核实 `docker-compose.yml:68` 为 `MINIO_API_CORS_ALLOW_ORIGIN: "*"`，`:86` 为 `M3_MINIO_PUBLIC_ENDPOINT: http://localhost:9000`。
- 未改任何 API / DTO / 错误码 / WS 事件 / 数据库表 / 迁移；前端按现有 M3A/M3B/M3C 契约消费。
- Task 4 修复了 `recorderUploader.ts` 的 recorder 监听器注册时序（内部实现变化，无 API/DTO 变化），已在实现中披露。

## 2. 实现摘要（Tasks 1–7）

| Task | 提交 | 内容 |
|---|---|---|
| 1 | `37ddbb0`..`b56425a` | 前端工具链与共享外壳：`api/client.ts`（`/api/v1`、dev identity 头、`ApiError`）、`stores/auth.ts`（`?dev_role=teacher`、sessionStorage）、`app/router.ts`、`layouts/ImmersiveLayout.vue`、错误页、`types/*`、Vitest + jsdom 配置 |
| 2 | `b56425a`..`b098829` | 学生任务列表 / 详情；`createSession` / `getSession` / `start` / `end` / `cancel`；按 mode 跳转课堂或面试，活动 session 可恢复 |
| 3 | `b098829`..`a242850` | 控制 WS 客户端：一次性 ticket、`ping`→`pong`、`seq` 去重、30 秒 grace、`session.resume {last_seq, recording_state}`；`classroomReducer.ts` 处理快照与事件 |
| 4 | `a242850`..`0b335bf` | 媒体采集与分片上传：三路 MediaRecorder、5 秒 timeslice、5 MB 分片、首片保留并在 `fix-webm-duration` 后重传、全部 ETag 完成后 complete、失败 / 权限拒绝路径 |
| 5 | `0b335bf`..`908c4a9` | 课堂页：三路录制状态、虚拟学生网格、WS 状态、提问 / 点名 / 回答 / 纪律、QA 结束、session 结束与上传完成等待、刷新 / `recording_state=lost` 失败态 |
| 6 | `908c4a9`..`ef3839b` | 面试页：摄像头 + 麦克风两路录制、结构化问题、面试计时、结束流程；仅公开虚拟学生字段 |
| 7 | `ef3839b`..`bbacb40` | 回看 / 回放：录制状态轮询与提交、只读已提交态、`ReplayPage` + `ReplayPlayer`（音频主时钟、manifest `offset_ms`、500 ms 检查 / >200 ms 校正、事件跳转、`?t=`、预签名刷新、播放位置保持）、`EventTimeline` / `ReplayControls` |

## 3. 验证结果（2026-11-06）

### 3.1 全量单测（最终审查修复后）

```text
npm --prefix frontend run test -- --run

Test Files  15 passed (15)
Tests       134 passed (134)
Duration    5.80s (environment 63%, import 23%, tests 11%, transform 2%, worker 1%)
```

- 0 failed / 0 skipped；测试覆盖 `api/client`、`app/router`、`stores/auth`、`utils/wsClient`、`utils/classroomReducer`、`utils/media`、`utils/recorderUploader`、`utils/replaySync`、课堂 / 面试 / 回看 / 任务页、回放组件与 `ReplayPage` 事件分页。
- 本机 Node 为 22.22.1，jsdom 31 的 engine 建议 `^22.22.2`；仅安装期警告，不影响本次运行。

### 3.2 生产构建

```text
npm --prefix frontend run build
vue-tsc -b && vite build
✓ 1741 modules transformed.
✓ built in 3.28s
```

- `frontend/dist/index.html` 生成（0.61 kB），`frontend/dist/assets/**` 分块产出；无类型错误。
- 唯一警告：主 chunk `index-CPE9u7Cz.js` 833.24 kB（gzip 261.56 kB）超过 500 kB 建议阈值；为 Element Plus 全量引入的既有警告，不是本次新增失败。
- `npm --prefix frontend run preview -- --host 127.0.0.1 --port 4173` 可正常提供 `dist`：`GET /student/tasks` 返回 200（614 bytes），并引用带 hash 的 JS/CSS。

### 3.3 Dev server / SPA 服务

```text
npm --prefix frontend run dev -- --host 127.0.0.1
VITE v8.2.2 ready in 1261 ms
➜ Local: http://127.0.0.1:5173/
```

- `GET /` → 200（415 bytes），含 `<div id="app">` 与 `/src/main.ts`。
- `GET /student/tasks` → 200（415 bytes），Vite history fallback 正常返回 SPA，而不是 404。
- Vite 代理 `/api`、`/ws` 到 `http://localhost:8080`（见 `frontend/vite.config.ts`）。

### 3.4 浏览器 smoke：BLOCKED（未通过，不声称通过）

**阻塞原因（精确）：**

1. **后端无法启动。** `docker --version` / `docker compose version` 均返回 `docker: command not found`；未安装 Docker Desktop（`/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe` 不存在），无 `podman`，无 `java`。因此 `docker compose up -d --build` 无法执行，PostgreSQL / Redis / MinIO / Spring Boot 均不可用。
2. **无 Linux 浏览器 / Playwright 浏览器。** `which google-chrome chromium chromium-browser` 无输出，`/usr/bin` 无 Chromium/Firefox，`~/.cache/ms-playwright` 不存在。虽然 Windows Chrome 可经 WSL interop 调用，但不能代替完整的 Linux 浏览器 + Playwright 手工流程。

**已完成的替代性检查（仅证明 SPA 外壳，不证明业务流程）：**

- 使用 Windows Chrome `--headless=new --dump-dom` 访问 dev server `http://127.0.0.1:5173/student/tasks`：Vue 已挂载（`<div id="app" data-v-app>`），页面渲染出「我的任务」；随后请求 `/api/v1/...` 经 Vite 代理得到 `HTTP_502`（:8080 无监听）。Chrome stderr 为 0 行。
- 同样方式访问生产预览 `http://127.0.0.1:4173/student/tasks`：Vue 已挂载，同样因无后端显示 502 错误态。
- 这两项只验证了「dev / preview 能提供 SPA、路由与 Vue 能挂载、代理生效」，**未验证**任务选择、摄像头 / 屏幕 / 麦克风授权、三路录制指示、WS 连接、课堂交互、上传完成、回放播放 / 跳转与提交只读流程。

**结论：浏览器 smoke = BLOCKED（后端与浏览器环境缺失），不计入通过；G7 E2E 仍未开始。**

### 3.5 Compose MinIO 配置

- 未修改 `docker-compose.yml`。已核实 MinIO CORS `MINIO_API_CORS_ALLOW_ORIGIN: "*"` 与公网端点 `M3_MINIO_PUBLIC_ENDPOINT: http://localhost:9000` 已在 M3C 计划 Task 3 配置。
- 由于本机无 Docker，MinIO 是否实际暴露 `ETag` 响应头无法在本次环境运行验证；Task 4 记录的该风险仍待有 Docker 的环境确认。

### 3.6 最终审查修复验证（2026-11-06 第二轮）

修复范围：2 Critical + 4 Important + 5 项次要项；未改后端、契约、设计文档或依赖。

| 项 | 修复 | 验证 |
|---|---|---|
| Critical：用户手势采集 | 课堂 / 面试页 mount 只加载 session 并渲染「开始录制 / 开始面试」门；点击后才 `acquireMedia`、创建 recorder/uploader、连接 WS，再走原有 `recording.ready` → `ready_ack` → `session.start` 流程。`in_progress` 刷新仍走 lost-recording 路径，不采集媒体。 | `ClassroomPage.spec` / `InterviewPage.spec` 新增门禁测试；RED/GREEN 见下 |
| Critical：preparing session 泄漏 | 采集失败、浏览器不支持、屏幕选择取消、recorder 创建失败、WS ticket/连接失败、ready ack 拒绝、uploader 启动失败、`session.start` 发送失败以及 preparing 状态离开页面时，均 best-effort `cancelSession(sessionId)`（只调用一次，且 `session.start` 已发送后不再取消）；任务详情页对 `submitted` / `closed` / 不支持浏览器禁用「开始试讲」并显示原因；409 `SESSION_ACTIVE_EXISTS` 文案改为「请从任务列表取消或继续该会话；如无法处理，请联系教师」。 | 课堂/面试页 4 类取消测试 + 任务详情 3 个门禁测试 + 409 文案断言 |
| Important：回放时间线 | 过滤 `session.time_tick` / `session.resumed` / `ping` / `pong` / `time.sync`；按 `after_seq` 继续取页，最多 20 页 / 2000 行，直到取得 >100 条有效事件或 feed 结束；仅在确实还有更多事件时显示截断提示。 | 新增 `ReplayPage.spec.ts`：tick 密集 fixture、短末页、20 页上限 3 条 |
| Important：提交确认 | `ElMessageBox.confirm` 提示提交不可修改且删除其他未提交尝试；提交中按钮禁用；422 同时展示 `missing_streams` 与 `invalid_streams` 并刷新录制状态。 | `ReviewPage.spec` 新增确认取消、422 双列表、60 s 停滞测试 |
| Important：离开守卫 | 录制期间注册 `beforeunload` 与 `onBeforeRouteLeave` 确认；停止 / 卸载时移除。课堂与面试页均覆盖。 | 两页各新增 unload guard 注册/移除测试 |
| Important：submitted/closed 门禁 | 见上 tasks started guard。 | 任务详情 3 个测试 |
| 次要项 | 回看轮询 60 s 无进展显示「录制可能已中断，请重做」；`ReplayPlayer` 在 `await master.play()` 后复查 `playing`/mounted 并增加 `disposed`；已提交会话不再显示无法复现的提交号（统一隐藏）；`ClassroomPage` 改用共享 `fetchWsTicket` / `messageOf`；清理 stale Task 7 注释。 | 对应测试 / 类型检查 / 构建 |

**RED/GREEN 证据（手势门禁与取消）：**

```text
# RED：仅回退两个页面的实现到 HEAD（测试保留新断言）
npm --prefix frontend run test -- --run \
  src/views/classroom/ClassroomPage.spec.ts src/views/classroom/InterviewPage.spec.ts

Test Files  2 failed (2)
Tests       9 failed | 22 passed (31)
# 失败包括：acquireMedia 在 mount 时已被调用（无门禁）、
# cancelSession 未被调用（采集失败 / recorder 失败 / 连接失败 / 离开 preparing）
```

```text
# GREEN：恢复实现后同一命令
Test Files  2 passed (2)
Tests       31 passed (31)
```

构建（最终）：

```text
npm --prefix frontend run build
✓ 1741 modules transformed.
✓ built in 2.46s
# 唯一警告：index 主 chunk 833.24 kB（gzip 261.72 kB）超过 500 kB 建议阈值（既有 Element Plus 全量引入警告）
```

## 4. 已知缺口 / 后续

- `offset_ms` 生产值仍为 0（M3C 契约缺口）；播放器已按 manifest 应用非零 offset 并有单测 / 组件测试，后端补齐后前端无需修改。
- 回放事件的 `session.time_tick` 等控制事件由前端过滤并按 `after_seq` 分页（最多 20 页 / 2000 行）；后端 `ReplayQueryService` 的 `NON_REPLAYABLE` 排除属于跨模块契约变更，本约束下未改后端，需求已转给 M3C Owner。
- 浏览器关闭 / 刷新时无法可靠等待 `cancelSession`（unmount 仅覆盖 SPA 导航）；preparing session 的浏览器级清理仍依赖后端 grace / attempt 上限。
- 没有 Playwright E2E；本计划明确不含浏览器自动化，E2E 由 Score/QA Owner 后续补齐。
- 没有完整 Element Plus 暗色主题：沉浸式深色顶栏上仍是浅色主题控件，Vite 脚手架残留 `HelloWorld.vue` / 示例资源未清理。
- `InterviewPage` 与 `ClassroomPage` 编排逻辑重复（约 780 行）；后续应抽取 room composable。
- 继承的重连竞态：握手期间（`recording.ready` / `session.start`）发生重连时不会重新驱动握手，课堂与面试页均受影响；属整个分支已接受的遗留项。
- 结束流程等待上传完成没有超时 / deadline；上传卡住时结束会一直等待。
- 上传进度只能显示已上传字节 / 分片数，无法显示百分比（`RecordingStreamView` 无总字节字段）。

## 5. 变更文件

### 5.1 本 Task 8 变更

| 文件 | 说明 |
|---|---|
| `docs/team/evidence/2026-11-04-m3-frontend-classroom-replay.md` | 本证据文件（新增） |
| `docs/team/02-进度看板.md` | 仅更新 M3B / M3C 阻塞文本并新增一条 §5 记录；G3—G8 与完成度未改 |
| `frontend/README.md` | dev / build / test / preview 命令、dev proxy 与 `?dev_role=teacher` 身份说明 |
| `server/README.md` | 增加前端 dev server 启动方式与代理说明（仅在需要处提及） |

### 5.1.1 最终审查修复（2026-11-06 第二轮）

| 文件 | 说明 |
|---|---|
| `frontend/src/views/classroom/ClassroomPage.vue` / `InterviewPage.vue` | 启动门、preparing cancel、录制离开守卫、共享 `fetchWsTicket` / `messageOf`、清理 stale 注释 |
| `frontend/src/views/classroom/ClassroomPage.spec.ts` / `InterviewPage.spec.ts` | 手势门禁、取消、unload guard 测试 |
| `frontend/src/views/replay/ReplayPage.vue` / `ReplayPage.spec.ts` | 控制事件过滤 + `after_seq` 分页 + 截断提示；新增测试文件 |
| `frontend/src/views/replay/ReviewPage.vue` / `ReviewPage.spec.ts` | 提交确认、422 双列表 + 刷新、60 s 停滞、隐藏提交号 |
| `frontend/src/views/task/StudentTaskDetailPage.vue` / `.spec.ts` | submitted/closed/浏览器门禁、409 文案 |
| `frontend/src/components/replay/ReplayPlayer.vue` | `disposed` / `playing` 复查 |
| `docs/team/evidence/2026-11-04-m3-frontend-classroom-replay.md` / `docs/team/02-进度看板.md` | 本轮证据与看板记录 |

### 5.2 Task 1–7 前端变更（相对计划基线 `37ddbb0`，共 61 个文件）

```text
新增（55）：
frontend/src/api/{client.ts,recordings.ts,replay.ts,sessions.ts,tasks.ts,wsTicket.ts}
frontend/src/api/client.spec.ts
frontend/src/app/{router.ts,router.spec.ts}
frontend/src/components/classroom/{CameraPreview,ClassroomControls,InterviewTimer,QuestionPanel,RecordingStatus,ScreenShare,VirtualStudentCard,VirtualStudentGrid,WsStatus}.vue
frontend/src/components/classroom/VirtualStudentCard.spec.ts
frontend/src/components/replay/{EventTimeline,ReplayControls,ReplayPlayer}.vue
frontend/src/components/replay/ReplayPlayer.spec.ts
frontend/src/layouts/ImmersiveLayout.vue
frontend/src/stores/{auth.ts,auth.spec.ts,interview.ts}
frontend/src/types/{api,event,recording,session}.ts
frontend/src/utils/{classroomReducer.ts,classroomReducer.spec.ts,media.ts,media.spec.ts,message.ts,recorderUploader.ts,recorderUploader.spec.ts,replayFormat.ts,replaySync.ts,replaySync.spec.ts,wsClient.ts,wsClient.spec.ts}
frontend/src/views/classroom/{ClassroomPage,InterviewPage}.vue 及其 .spec.ts
frontend/src/views/errors/{ForbiddenPage,NotFoundPage}.vue
frontend/src/views/replay/{ReplayPage,ReviewPage}.vue、ReviewPage.spec.ts
frontend/src/views/task/{StudentTasksPage,StudentTaskDetailPage}.vue、StudentTaskDetailPage.spec.ts

修改（6）：
frontend/package.json、frontend/package-lock.json、frontend/vite.config.ts
frontend/src/main.ts、frontend/src/App.vue、frontend/src/style.css
```

## 6. 进度口径

- 本切片只增加实现与测试证据；G2 设计冻结仍未关闭，G3—G8 均未标记，模块完成度保持 **10%**。
- 测试进度表中 M3B / M3C 仍为 UT / IT / 契约 3 层通过（60%），E2E 未开始；前端单测不改变 `docs/design/10` 的模块用例口径。
- 进度看板仅更新 M3B / M3C 的“阻塞 / 下一步”文本，并新增一条 §5 更新记录；未改其他模块行、Gate 或完成度。