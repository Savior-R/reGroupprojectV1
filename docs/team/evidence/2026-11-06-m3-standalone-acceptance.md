# 2026-11-06 M3 独立浏览器验收（不依赖 M1 / M2 / M4 / M7）

> 目的：验证 M3 模块在只有 M3 实现的情况下，学生从任务列表 → 课堂/面试录制 → 结束 → 回看 → 提交 → 回放全链路可用。
> 环境：Docker Compose `app`（docker profile，`DevDataConfig` 内存课程 + 学生 + 题库、dev 身份头）、Vite dev server、Playwright Chromium headless（fake camera/mic/screen）、真实 MinIO。
> 结论：**free 课堂与 interview 两条学生链路均通过**；录制的 camera / screen / audio 对象、时长、非零 `offset_ms`、提交与回放均落库并可回放。该验收不是 `design/10` §7.7 的完整 E2E-01/02（不覆盖 M1 登录、M2/M5 正式实现、M4 评测、M7 评分）。

## 1. 环境与启动

```bash
# 1. 后端全栈（postgres / redis / minio / app）
docker compose up -d app
curl -s http://localhost:8080/actuator/health

# 2. 前端 dev server（Vite 代理 /api、/ws 到 localhost:8080）
npm --prefix frontend run dev -- --host 127.0.0.1
# http://127.0.0.1:5173

# 3. Playwright（临时目录，不写入仓库）
mkdir -p /tmp/m3-e2e && cd /tmp/m3-e2e
npm init -y && npm i playwright@1.65.0
npx playwright install chromium
```

WSL 缺少 `libnspr4 / libnss3 / libasound2` 时，本轮将 Ubuntu 包解压到 `/tmp/m3-libs/root`，运行脚本时设置：

```bash
LD_LIBRARY_PATH=/tmp/m3-libs/root/usr/lib/x86_64-linux-gnu node acceptance.mjs
```

## 2. 任务准备（M3A API，dev 身份）

教师头：`X-Account-Id=...0010`、`X-School-Id=...0001`、`X-Account-Type=TEACHER`。课程固定为 `DevDataConfig` 的 `...0100`。

1. `POST /api/v1/courses/...0100/tasks` 创建 free / interview 任务（free 权重 `content/expression/interaction/board/time`；interview 权重 `content/expression/time`，且 `duration_recommended = duration_max = question_config.total_duration_seconds`）。
2. `POST /api/v1/tasks/{id}/material` 上传 `text/plain` 参考材料（发布要求材料存在）。
3. `POST /api/v1/tasks/{id}/publish`，`assigned_count=2`。
4. 学生任务列表使用 `...0020` 学生身份自动可见。

## 3. 浏览器流程与结果

### 3.1 Free 课堂

脚本：`2026-11-06-m3-standalone-acceptance/acceptance.mjs`

```bash
cd /tmp/m3-e2e
TOPIC='M3 独立验收试讲 3' LD_LIBRARY_PATH=/tmp/m3-libs/root/usr/lib/x86_64-linux-gnu \
  node acceptance.mjs
```

结果：

```text
- open student task list ... ok
- open task detail ... ok
- create session and open classroom gate ... ok
- start recording (fake camera/mic/screen) ... ok
- end session and wait for review page ... ok
- wait for three uploaded streams ... ok
- submit recording ... ok
- render read-only replay ... ok
task after submit: assignment_status=submitted
ACCEPTANCE PASSED
```

数据库核对（session `01a1b0ad-e947-...`）：

| 项 | 值 |
|---|---|
| session | `submitted`，`duration_ms=6547` |
| camera | `uploaded`，`duration_ms=10550`，`offset_ms=10`，469542 B |
| screen | `uploaded`，`duration_ms=10562`，`offset_ms=11`，561862 B |
| audio | `uploaded`，`duration_ms=10562`，`offset_ms=12`，162566 B |

回放页显示 `00:06` 总时长、screen 主画面 + camera 小窗、事件时间线；`session.time_tick` / `ping` 等控制事件不在列表中。

截图：[task list](2026-11-06-m3-standalone-acceptance/01-task-list.png)、[classroom recording](2026-11-06-m3-standalone-acceptance/04-classroom-recording.png)、[submitted replay](2026-11-06-m3-standalone-acceptance/07-submitted-replay.png)。

### 3.2 结构化面试

脚本：`2026-11-06-m3-standalone-acceptance/interview.mjs`

```bash
TOPIC='M3 独立验收面试' LD_LIBRARY_PATH=/tmp/m3-libs/root/usr/lib/x86_64-linux-gnu \
  node interview.mjs
```

结果：

```text
- open student task list ... ok
- open interview task detail ... ok
- create session and open interview gate ... ok
- start interview recording (fake camera/mic) ... ok
- end interview and wait for review page ... ok
- wait for two uploaded streams and submit ... ok
- render read-only interview replay ... ok
INTERVIEW ACCEPTANCE PASSED
```

数据库核对（session `01a1b0af-96f9-...`）：

| 项 | 值 |
|---|---|
| session | `submitted`，`duration_ms=6587` |
| camera | `uploaded`，`duration_ms=12264`，`offset_ms=37`，544359 B |
| audio | `uploaded`，`duration_ms=12274`，`offset_ms=38`，190594 B |

截图：[interview gate](2026-11-06-m3-standalone-acceptance/10-interview-gate.png)、[interview recording](2026-11-06-m3-standalone-acceptance/11-interview-recording.png)、[interview replay](2026-11-06-m3-standalone-acceptance/12-interview-replay.png)。

## 4. 覆盖范围

- M3A：教师创建 free / interview 任务、上传材料、发布、学生可见。
- M3B：session 创建、`recording.ready` / `session.start`、课堂控制、结束、WS 事件、snapshot、状态机。
- M3C：三路 / 两路 MediaRecorder（fake）、multipart 上传、complete、时长与 offset 落库、回看轮询、提交事务、只读回放、事件过滤。
- 前端：任务列表 / 详情 / 课堂 / 面试 / 回看 / 回放路由与手势启动门。

## 5. 未覆盖 / 边界

- 不是完整 E2E-01/02：M1 登录、M2 真实课程、M5 真实题库、M4 评测、M7 评分未实现；`e2e/**` 仍由 Score/QA Owner 维护。
- 浏览器为 Linux Chromium headless + fake media；真实摄像头 / 屏幕共享权限、真实设备切换、Safari 未覆盖。
- 未覆盖网络中断 / 30 秒 grace / 页面刷新丢失 / 保留期到期后的浏览器行为；这些由后端 IT 与前端单测覆盖。
- 未做性能 / 并发 / 多学生 NFR。
- 本轮首次运行使用了旧的 `app` 镜像，三路并行 start 触发 `uq_recordings_session`；`docker compose build app` 重建当前代码后通过。该记录说明验收必须针对当前镜像执行。

## 6. 复现说明

`acceptance.mjs` / `interview.mjs` 保存在本证据目录，仅作为手工验收脚本；依赖本机 Playwright 与 `/tmp/m3-libs`，未接入 CI。`e2e/**` 的正式 Playwright 套件仍按 `design/10` §7.7 由 Score/QA Owner 建立。
