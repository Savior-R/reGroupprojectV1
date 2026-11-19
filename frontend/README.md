# 三尺讲台前端（Vue 3 + TypeScript + Vite）

全站前端：M1 登录 / 组织、M2 课程、M3 任务与课堂录制、M4 报告、M5 题库、M6 训练、M7 评分统计。
登录后统一由 `src/layouts/AppShell.vue` 提供角色导航；课堂 / 面试 / 回看 / 训练房间使用沉浸式布局。

The backend lives in `server/`; start the full stack with `docker compose up -d --build`
from the repository root before using the pages.

## Requirements

- Node.js 22.22+ (developed on v22.22.1) and npm 9+
- A running backend at `http://localhost:8080` (API + control WebSocket)
- Chrome or Edge for recording / replay; other browsers show a blocking message

## Commands

> 课堂 / 面试 / 训练录制依赖 `getUserMedia` / `getDisplayMedia`，浏览器只允许在
> `localhost` 或 HTTPS 安全上下文使用。用 Windows 浏览器验收时请走
> `http://localhost:5173`；WSL 的 LAN IP 可用于查看普通页面，但录制页会被浏览器拒绝。

From the repository root:

```bash
# 一键启动后端 + Vite（推荐；会处理 .env / demo DB / eval-worker）
./scripts/dev.sh up --build

# 只操作前端
npm --prefix frontend install
npm --prefix frontend run dev -- --host 127.0.0.1   # http://127.0.0.1:5173
npm --prefix frontend run test -- --run              # Vitest (jsdom)
npm --prefix frontend run build                      # vue-tsc -b && vite build -> frontend/dist
npm --prefix frontend run preview -- --host 127.0.0.1
```

The same scripts work from `frontend/` (`npm run dev`, `npm run test -- --run`,
`npm run build`, `npm run preview`).

## Dev proxy

`vite.config.ts` proxies `/api` and `/ws` to `http://localhost:8090` (Compose maps
container `8080` to host `8090`). Override with `VITE_API_TARGET`, e.g.
`VITE_API_TARGET=http://localhost:8080` for a local `bootRun`. If the backend is not
running, pages render their error state with `HTTP_502`.

## Dev identity (dev/test fallback; login is required in docker)

登录后 `src/stores/auth.ts` 从 JWT 同步真实身份，`src/api/client.ts` 同时发送
`Authorization: Bearer` 与 `X-Account-*`。后端 `docker` profile 只接受 Bearer JWT；
`X-Account-*` 仅在 `dev` / `test` profile 作为兜底。

未登录时 M3 页面仍可用 dev preset 调试，默认学生；首次打开时加 `?dev_role=teacher`
可切换教师身份：

```text
http://127.0.0.1:5173/student/tasks?dev_role=teacher
```

- Student: `00000000-0000-0000-0000-000000000020` / `STUDENT`
- Teacher: `00000000-0000-0000-0000-000000000010` / `TEACHER`
- School: `00000000-0000-0000-0000-000000000001`

The selected role is persisted in `sessionStorage` under `m3.dev-identity`; the query
parameter is only honoured when no role is stored yet. Clear that key to switch roles.

## Routes

- `/login`、`/register`、`/register/oauth` — M1 认证
- `/courses`、`/courses/:courseId` — 课程列表 / 详情；教师从详情进入「课程任务」
- `/teacher/courses/:courseId/tasks`、`/new`、`/:taskId` — 教师任务列表 / 新建 / 编辑发布
- `/student/tasks`、`/student/tasks/:taskId` — 学生任务
- `/student/sessions/:sessionId/{classroom,interview,review}` — 课堂 / 面试 / 回看提交
- `/student/training/**` — 训练首页、配置、课堂、回看、记录；训练报告走 M4 报告组件
- `/teacher/question-bank` — M5 题库
- `/student/results`、`/teacher/courses/:courseId/.../grading|stats` — M7 成绩与评分统计
- `/evaluations/:id/report`、`/submissions/:id/report` — M4 共享报告终点
- `/admin/setup`、`/admin/org`、`/admin/teachers` — M1 管理端
- `/403`、`/404`、catch-all 404

旧文档路径 `/question-bank`、`/teacher/training-templates`、`/teacher/evaluations/:id`、
`/admin/courses`、`/student`、`/teacher` 保留重定向。规范路径以 `src/app/router.ts` 为准。

## Build

`npm run build` 已通过；完整 Vitest / E2E 结果在整合验收后统一补录。