# 2026-11-16 全站前端编排与 M6 / M4 运行时整合

> 授权：用户于 2026-11-16 明确作为全项目整合负责人，拥有修改各模块的权利；本轮按用户确认的四项决策执行：
> 1. 统一 `AppShell`；2. 统一米白 + 墨绿浅色主题；3. 教师端补齐任务创建 / 编辑页面；4. 训练记录详情 + M4 报告组件，只保留一个规范报告入口。
> 额外要求：M6 接入 M3 录制舞台与回放；先跑起整体网站供人工检查，最后再跑测试。

## 1. 全站外壳与路由

- 新增 `frontend/src/layouts/AppShell.vue`：品牌、按 JWT `roles` 并集裁剪的主导航、账号安全、退出登录。
- `frontend/src/App.vue` 按 `meta.layout` 选择布局：
  - `app`：AppShell；`auth`：登录 / 注册；`immersive`：课堂 / 面试 / 回看 / 训练课堂；`blank`：403 / 404。
- 删除各管理页内的 `AdminShell` 包裹与 `views/admin/AdminShell.vue`，避免双重外壳。
- 导航：
  - 学生：课程、任务、训练、我的成绩；
  - 教师：课程、题库、训练模板；
  - 管理员：学校与组织、教师管理、课程管理；
  - 多角色取并集。
- 落地页统一由 `homeFor()`：管理员（区分是否建校）→ 教师 `/courses` → 学生 `/student/tasks`。
- 规范路径 + 兼容重定向：`/question-bank`、`/teacher/training-templates`、
  `/teacher/evaluations/:id`、`/admin/courses`、`/student`、`/teacher`、
  `/training/records/:id/report`。
- M7 教师页补 `roles: ['teacher','admin']`，M5 题库 `teacher/admin`，M6 教师模板仅 `teacher`；
  未登录 / 越权由守卫先拦。

## 2. 教师任务创建 / 编辑 / 发布

- 新增 `frontend/src/views/task/TeacherTaskListPage.vue`：
  - 课程任务列表、状态 / 模式筛选、发布 / 关闭 / 删除、评分 / 统计 / 详情入口。
- 新增 `frontend/src/views/task/TeacherTaskEditorPage.vue`：
  - 自由 / 情景 / 面试三种模式；
  - 课题、教材版本、时长、权重（合计 100）、情景角色 / 情境；
  - 虚拟学生编辑（复用 M6 `VirtualStudentEditor`）；
  - `.txt/.md` 参考材料上传与解析；
  - 面试题目从 M5 题库多选（1—10 道），总时长与题目配置联动；
  - 草稿保存、发布、发布后只读、评分 / 统计 / 关闭入口。
- `frontend/src/api/tasks.ts` 增加教师任务 / 材料 API 与 DTO。
- 课程详情 `CourseDetailPage.vue` 增加「课程任务」与「我的任务」入口。
- 课程列表教师视图增加「任务 / 评分」直达按钮与提示，避免教师必须绕到课程详情才能找到评分入口。
- 修复验收中的课程归档副作用：`语文教学论` 被归档后教师课程列表只剩另一门课，导致看不到任务 / 评分入口；已通过管理员恢复接口恢复课程。
- 路由：`/teacher/courses/:courseId/tasks`、`/new`、`/:taskId`，`/edit` 重定向到详情。
- M3 在 `docker` profile 下改用 JDBC 读取 M2 课程 / 老师组 / 在课学生与 M5 题库，
  不再只认识固定 dev 课程；`DevDataConfig` 仅保留给 `dev/test`。

## 3. M6 训练接入 M3 录制舞台与回放

### 3.1 后端

- `SessionType` 新增 `TRAINING`；`Session.taskId`、`SessionView.taskId` 允许为空。
- `SessionStatus` 新增 M6 终态 `SELECTED` / `EVALUATED`：M6 在“保留训练”和“评测完成”后会把共享
  `m3_classroom.sessions` 行改成这两个状态；M3 之前只认识 task 状态，导致训练记录回放报
  `未知会话状态`（前端显示“未知会话”）。
- `SessionRepositoryAdapter` 支持 `task_id=null` 的训练行。
- `SessionStartGuard` 对训练 session 跳过任务校验，仅在绑定课程时校验课程窗口。
- 新增 `TrainingSessionConfigPort` 与 `TrainingSessionConfigJdbcAdapter`：
  - M3 从 `m6_training.training_session_meta` 读取 effective config；
  - 构造课堂 actor 使用的 `TaskSnapshot`（topic、时长、权重、虚拟学生）；
  - 通过 `SchoolDirectoryPort` 解析学生学校。
- `SessionRuntimeRegistry` 训练分支使用上述快照构建 actor。
- `RecordingMutationService` 训练分支使用
  `recordings/{school_id}/training/{session_id}/{stream}.webm`，不再要求 task 行。
- 前端训练配置页直接进入 `/student/training/sessions/:id/classroom`；
  `TrainingRoomPage` 旧路由重定向到课堂，页面文件删除。
- 训练回看页嵌入 M3 `ReplayPage`；记录详情「查看回放」回到训练回看页。

### 3.2 验证

使用学生 `student.a01` 的 JWT 与真实接口完成：

1. `POST /api/v1/training/sessions` 创建自由训练；
2. `POST /sessions/{id}/ws-ticket` + `wscat/Node WebSocket` 连接；
3. 收到 `session.snapshot`（topic、虚拟学生均来自 M6 配置），发送 `recording.ready` / `session.start`；
4. 收到 `recording.ready_ack`、`session.started`、`session.time_tick`；
5. 三路 multipart start → part-url → PUT → complete 全部 `uploaded`；
6. `POST /sessions/{id}/end` → `ended`，M6 `GET /training/sessions/{id}` 同步为 `ended`；
7. `GET /sessions/{id}/replay` 返回 screen / camera / audio 三个预签名流；
8. `POST /training/records` 选中记录；
9. `eval-worker` 消费评测，训练记录变为 `evaluated`，M4 报告返回 `partial`（测试音频无有效转写 / 帧）。
10. 选中后 M3 session 状态为 `selected`；`GET /sessions/{id}` 200，`GET /sessions/{id}/replay`
    返回 screen / camera / audio 三路（用户实际免费训练记录同样验证通过）。

## 4. M4 评测 worker 与事件修复

- `docker-compose.yml` 新增 `eval-worker` 服务（`python -m app.worker.main`）：
  - Redis `evaluation:jobs` / `eval-workers`；
  - 依赖 `redis`、`app`；`AI_MOCK` 与 `SERVICE_TOKEN` 与 app 一致。
- `ai/pyproject.toml` 增加 `redis>=5,<6`（worker 运行所需）。
- 修复 M6 → M4 事件类型不一致：
  - M6 `TrainingEvaluationJdbcAdapter` 改为发布 `com.shijiangtai.m4eval.domain.TrainingRecordSelectedEvent`；
  - M4 `EvaluationTriggerListener` 改为 `@TransactionalEventListener(AFTER_COMMIT)`，避免读未提交记录；
  - 事件只携带 `trainingRecordId/sessionId/studentAccountId`，M4 自行读取训练配置。
- 修复 M6 `Instant.plus(RETENTION_MONTHS, ChronoUnit.MONTHS)` 抛 `UnsupportedTemporalTypeException`，
  改为 `atZone(UTC).plusMonths(...)`。
- 之前评测一直 `pending` 的根因：FastAPI `ai` 服务不消费 Redis Stream，Compose 缺 worker；
  现已补齐。

## 5. 报告 / 链接 / 主题收口

- M4 报告为共享终点：
  - `/evaluations/:id/report`、`/submissions/:id/report`；
  - 训练报告统一 `/student/training/records/:recordId/report`，旧路径重定向；
  - 报告页统一支持 `?from=` 返回。
- 学生成绩详情：内部链到 `/submissions/:id/report` 与 `/student/sessions/:id/review`；
  M7 DTO 增加 `session_id`。
- 评分表格行增加「AI 报告」入口，携带 `from` 返回评分页。
- 评分表格行增加「回放」按钮：M7 `grading-submissions` 每行补 `session_id`，点击后在评分页内用
  M3 `ReplayPage` 打开学生 screen / camera / audio 三路回放；M3 `SessionAccessPolicy` 仍校验
  课程老师组与已提交状态，教师不能看未提交 / 非本组 session。
- 回看页提交成功增加「查看成绩」。
- 训练记录详情移除内联报告，只保留摘要 + 完整报告按钮。
- M4 / M6 / M7 页面硬编码深色文字 / 背景替换为 `--m1-*` token；
  沉浸式课堂 / 面试 / 回看保留深色舞台。
- M7 dev 角色切换在 JWT 登录后隐藏。

## 6. 验证结果

- 前端 `npm --prefix frontend run build`：通过（仅既有 chunk 体积警告）。
- 后端 `docker build --network=host -t shijiangtai-app:latest ./server`：通过。
- 容器：postgres / redis / minio / ai / app / eval-worker 全部启动；`/actuator/health` UP。
- 教师任务：
  - `GET /courses/{id}/tasks` 200，返回 demo 任务；
  - 新建草稿、上传 >20 字 `.txt` 材料、发布成功，分配 5 名学生；
  - M7 `grading-submissions` / `tasks/{id}/stats` / `courses/{id}/stats` 均 200。
- 训练：
  - M3 `GET /sessions/{id}` 返回 `session_type=training`、`task_id=null`；
  - WS 快照 / 开始 / tick 正常；
  - 三路训练录制上传成功，对象键包含 `/training/`；
  - 回放 manifest 三路齐全；
  - 选中记录后 worker 完成评测，M4 报告 200（partial）。
- 路由 HTTP：`/login`、`/courses`、教师任务列表 / 新建 / 详情、学生训练、训练记录 / 报告、学生成绩均 200。

## 7. 本地启动脚本

新增 `scripts/dev.sh`、`scripts/compose.demo.yml`、`scripts/README.md`：

- `up`：检查 Docker，必要时从 `server/.env.example` 生成 `.env` 并补齐随机密钥，
  启动 postgres / redis / minio / ai / app / eval-worker，等待 `/actuator/health`，
  再启动 Vite 并打印地址与 demo 账号；
- `up --build`：源码改动后重建 app / ai / eval-worker 镜像；
- `status` / `logs app|eval-worker|frontend` / `stop` / `down` / `restart`；
- `reset-demo --yes`：重建 demo 数据库、重跑 V900/V901、上传 demo 媒体（有 ffmpeg 时）；
- `frontend`：只启动 Vite；
- Vite PID / 日志写入 `.run/`（已加入根 `.gitignore`）。

已验证 `down` → `up` 全流程：容器重建、后端健康、Vite 启动、demo 数据卷保留。

## 8. 已知问题 / 待办

1. 按用户要求，本轮尚未运行完整后端 `gradle test`、前端 Vitest 全量、Playwright E2E；人工验收后再执行。
2. 测试录音为极小占位文件，评测结果为 `partial`（无转写 / 帧）；真实浏览器录制应产生更完整结果。
3. 评测 worker 启动前积压的旧消息可能重复投递；本轮已通过新事件与 worker 修正，历史脏消息需在验收前清理 Redis Stream。
4. M6 训练房间依赖浏览器摄像头 / 屏幕 / 麦克风权限；无设备时只能验证 API / WS。
5. 教师任务页的「任务配置」未覆盖任务编辑后的版本历史 / 快照对比；发布后只读。
6. 文档 09 的旧路径已补重定向，但其他历史文档仍可能引用旧路径，后续逐步统一。
7. demo seed 的训练记录 `a0000000-...0011` 没有实际 `recordings` 行 / MinIO 对象；点击「查看回放」会返回
   `RECORDING_INCOMPLETE`。这是 demo 数据缺口，不是 selected/evaluated 状态 bug；真实免费训练记录已有三路录像可正常回放。
