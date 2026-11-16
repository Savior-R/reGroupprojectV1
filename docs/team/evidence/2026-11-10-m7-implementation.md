# 2026-11-10 M7 教师评测与统计实现证据

> Owner：Score/QA Owner　模块：M7　分支建议：`feat/m7-score`　日期：2026-11-10
> 依据：`docs/requirements/M7-教师评测与数据统计.md`、`docs/design/07-M7-教师评测与数据统计.md`、`docs/implementation/06-M7-实现设计.md`、`docs/design/02/12/10/09`

## 1. 完成范围

### 1.1 后端

新增 `server/src/main/java/com/shijiangtai/m7teacher/**`：

- 领域：`GradingConfig`、`TeacherScore`、`FinalScore`、`FinalScoreCalculator`、`Rubric`、`ScoringMode`、`FinalScoreStatus`、`M7Exception` / `M7ErrorCode`、`EvaluationTerminalEvent`。
- 应用：`GradingConfigService`、`TeacherScoreService`、`TeacherScoreBatchService`、`FinalScoreService`、`GradingSubmissionQueryService`、`StatisticsQueryService`、`LeaderboardQueryService`、`StudentResultQueryService`、`CsvExportService`、`M7AccessPolicy`。
- 基础设施：`GradingConfigRepositoryAdapter`、`TeacherScoreRepositoryAdapter`、`FinalScoreRepositoryAdapter`、`UpstreamQueryDao`、`M7JsonCodec`、`M7Config`、`M7EvaluationTerminalAdapter`。
- API：评分配置、评分名单、单条 / 批量评分、任务 / 课程统计、排行榜、CSV 导出、学生本人成绩；错误码与 07 §11.7 一致，错误体为 `{code, message, details, trace_id}`。

实现的 P0 契约：

- 默认 70/30、`dimension` 模式；GET / 名单懒创建；权重和、越界、模式校验。
- 首次评分写 `locked_at`；未锁定改配置重算；已锁定 409；清空评分解锁并重算 `pending_*`。
- 维度 / 总分评分；服务端按 M3 rubric 权重计算教师总分；评语 ≤ 1000 字；`version` 乐观锁 409；批量 ≤ 100 行、逐行结果、部分成功。
- 最终总分 / 最终维度分；权重 0 不要求分数；状态优先级 `ai_partial → ai_failed → pending_ai → pending_teacher → completed`；只有 `completed` 写最终分 / 维度分。
- 任务 / 班级 / 课程统计；只统计 `completed`；薄弱项最低 2 项、<60 标记、样本 <3 标记。
- 排行榜 `final_total DESC NULLS LAST, teacher_total DESC NULLS LAST, ai_total DESC NULLS LAST, student_account_id ASC`。
- CSV UTF-8 BOM、CRLF、RFC 4180 转义、公式注入前置单引号、10 000 行上限、空结果表头、动态维度列。
- 学生本人结果；pending 只返回状态，partial / failed 返回原因；管理员 403；跨校 / 非老师组拒绝。
- 只读 M1/M2/M3/M4；M7 不写回上游。归档课程只读，任务关闭仍可评分。
- M4 `EvaluationTerminalEvent` 本地契约适配器 + 每 5 分钟 `FinalScoreReconciler` 丢失事件兜底；对账 / 终态事件逐条在独立事务重算，避免单行失败污染整批。
- 任务级并发锁使用 JPA `EntityManager` 原生 `SELECT ... FOR UPDATE`，与 Hibernate 事务共用连接；`JdbcTemplate` 仅用于只读上游查询。

### 1.2 前端

新增：

- `frontend/src/views/grading/TaskGradingPage.vue`：名单、摘要、班级 / 状态 / 关键词筛选、评分抽屉、评分配置抽屉、清空确认、批量评分、CSV 导出、`pending_ai` 4 秒轮询。
- `frontend/src/views/stats/TaskStatsPage.vue`：任务摘要、维度平均分条形图、薄弱项、排行榜分页、导出。
- `frontend/src/views/stats/CourseStatsPage.vue`：课程合并平均分、任务明细、班级筛选、跳转任务统计。
- `frontend/src/views/grading/StudentResultsPage.vue`、`StudentResultDetailPage.vue`：学生本人成绩列表 / 详情（AI / 教师 / 最终三卡、维度、评语、报告 / 回放入口）。
- `frontend/src/views/grading/api.ts`、`ui.ts`、`M7PageHeader.vue`：M7 API 客户端、标签 / 格式化、页面头与开发角色切换。

路由（共享层，Score/QA Owner）：

- `/teacher/courses/:courseId/tasks/:taskId/grading`
- `/teacher/courses/:courseId/tasks/:taskId/stats`
- `/teacher/courses/:courseId/stats`
- `/student/results`、`/student/results/:submissionId`

## 2. 验证命令与结果

### 2.1 后端编译

```bash
cd server
JAVA_HOME="<JDK21>" gradle compileJava --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`（2026-11-10）。

### 2.2 后端 M7 单元测试

```bash
cd server
JAVA_HOME="<JDK21>" gradle test --tests 'com.shijiangtai.m7teacher.*' --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`；3 个测试类 / 17 条用例，0 failure / 0 error：

- `FinalScoreCalculatorTest`：10 条（70/30、权重 0、状态优先级、总分模式、AI 缺失、四舍五入）。
- `RubricTest`：3 条（默认维度、权重和、未知维度）。
- `CsvExportServiceTest`：3 条（公式注入、RFC 4180、普通文本）。

### 2.3 前端生产构建

```bash
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run build
```

结果：`vue-tsc -b && vite build` 通过；`✓ 1769 modules transformed`（2026-11-10）。

### 2.4 前端现有单测回归

```bash
cd frontend
npx vitest run
```

结果：18 个测试文件 / 143 条用例全部通过（2026-11-10）。M7 页面未新增 Vitest 用例（测试后置）。

### 2.5 PostgreSQL 17.6 + demo seed API smoke

本机临时启动 PostgreSQL 17.6（端口 5433），应用以 `docker` profile 启动并叠加 `db/demo` Flyway 位置，加载 V0—V12 + V900 demo seed；`m3.storage=memory`、`m3.ticket-store=memory` 避免依赖 MinIO / Redis。

```bash
SPRING_PROFILES_ACTIVE=docker \
SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/shijiangtai?stringtype=unspecified' \
SPRING_FLYWAY_LOCATIONS='classpath:db/migration,classpath:db/demo' \
gradle bootRun --args='--m3.storage=memory --m3.ticket-store=memory'
```

实测结果（教师 `20000000-...0011`，任务 `60000000-...0001`）：

| 场景 | 结果 |
|---|---|
| GET `grading-config` | 200；默认 70/30、dimension、`has_scores=true`、`can_edit=false` |
| GET `grading-submissions` | 200；5 条 demo 提交，completed / pending_ai / pending_teacher / ai_partial / ai_failed 各 1 |
| GET `stats` / `leaderboard` / `courses/{id}/stats` | 200；average 84.7 / 排行榜 1 条 / 课程 pooled 85.1 |
| GET `export` | 200；UTF-8 BOM `EF BB BF`、动态维度列、`Content-Disposition` 文件名 |
| PUT 教师评分（dimension，新建 version=0） | 200；教师总分 77.8，最终 `pending_ai`；配置自动写 `locked_at` |
| 同提交件 version=0 再保存 | 409 `TEACHER_SCORE_CONFLICT` |
| 同提交件 version=1 再保存 | 200；version=2 |
| 配置 PUT（已锁定） | 409 `GRADING_CONFIG_LOCKED` |
| 批量保存 3 行（2 成功 + 1 不存在提交件） | 200；`success_count=2`、`failure_count=1`，成功行立即重算 |
| DELETE 清空评分 `{confirm:true}` | 200；删除 3 条、解锁、重算 5 条 |
| 清空后配置 PUT 60/40 | 200；config_version 2，所有最终分重算 |
| 权重和 90 / 总分 101.0 / 80.05 | 422 `GRADING_CONFIG_INVALID` / `TEACHER_SCORE_INVALID` |
| 总分模式（50/50）评分 | 200；final 85.1，最终维度使用 AI 维度 |
| `ai_weight=0` + 教师总分 | 200；AI pending / completed / partial 均忽略，最终 = 教师分，维度为空 |
| AI partial + 教师评分 | 200；保留教师分，`status=ai_partial`，final 为空 |
| 归档课程 | GET config/stats/export 200；PUT config/score 422 `COURSE_ARCHIVED` |
| 草稿任务 | GET config 422 `TASK_NOT_DRAFT` |
| 学生本人结果 | 200；他人结果 403；管理员 403；教师学生列表 403；跨校 403 |
| CSV 中文评语含逗号/引号 | 正确 UTF-8、RFC 4180 双引号转义 |
| 删除 `final_scores` 行走对账 | 重启后 60 s 内 `FinalScoreReconciler` 补回 `pending_teacher` 行，统计恢复 |

说明：`/actuator/health` 在无 Redis 时返回 503（Redis health indicator），M7 API 不受影响；正式 IT / 契约 / E2E 仍需按 `10` §6.9 补齐。

## 3. 未执行 / 风险

- **本机工具链**：未安装独立 Gradle 8.14 / JDK21；构建使用本机 Android Studio JBR 21 与缓存 Gradle 9.5.0（项目 `build.gradle.kts` 仍声明 Java 21 toolchain，Compose test 服务仍为 gradle:8.14.3-jdk21）。首次编译需关闭 JVM 证书吊销检查以访问 Maven Central。
- **正式 IT / 契约 / E2E 未实现**：已完成临时 PostgreSQL + demo seed 的 API smoke（§2.5），但 `M7-T01—T11` 的自动化集成 / 契约 / E2E 测试代码尚未编写；仍需在项目 CI / Compose 环境补齐。
- **Docker daemon 未运行**：Compose 全栈与正式 `gradle test`（依赖 PostgreSQL 容器）未执行；本机使用临时 PostgreSQL 17.6 实例替代。
- **>200 人异步重算未实现**：配置修改 / 清空当前同步逐提交件重算；设计要求的 >200 人异步 job / 202 响应未实现，演示规模（≤200）可用，后续补 `FinalScoreRecomputeJob`。
- **M4 事件发布待接入**：M7 已定义 `EvaluationTerminalEvent` 与适配器；M4 实现后需按契约发布。事件丢失由 5 分钟对账兜底。
- **前端角色切换为开发态**：仓库尚无 M1 登录与 AppShell，M7 页面使用现有 dev identity（header）与页面头角色切换；正式接入 M1 后删除。
- **前端路径说明**：M7 API 客户端与共享 UI 辅助放在 `views/grading/**` 内，避免修改 `frontend/src/api/**`；路由属于 Score/QA Owner 的共享层，已更新并需在合并前复核。
- **依赖安装副作用**：为执行构建运行了 `npm install`，`frontend/package-lock.json` 的 `resolved` URL 已从镜像地址规范回 `registry.npmjs.org`；未修改依赖版本范围。`node_modules/`、`frontend/dist/`、`server/build/` 为本地构建产物，未纳入交付。

## 4. 演示 / E2E seed

- 复用现有 `server/src/main/resources/db/demo/V900__demo_seed.sql` 中的 M7 种子：任务 `60000000-...0001`、提交件 `71000000-...0001—0005`、AI 评测 `80000000-...0001—0005`、教师评分 `90000000-...0011`、最终分五条（completed / pending_ai / pending_teacher / ai_partial / ai_failed）。
- 启动 demo profile 后，教师 `20000000-0000-0000-0000-000000000011` 可访问任务评分 / 统计 / 导出；学生 `20000000-...0101—0105` 可查看本人结果。
- 未新增 seed 文件，避免修改 Foundation Owner 的 `db/demo` 路径。

## 5. 跨模块与 Owner 说明

- 修改文件均属 M7 允许路径（`m7teacher/**`、`views/grading/**`、`views/stats/**`、`docs/team/evidence/*m7*`），外加 `frontend/src/app/router.ts`。
- `frontend/src/app/router.ts` 属于 `docs/team/01` §3.3 前端共享层，Owner 为 Score/QA Owner（本 Prompt 使用者），不涉及 M1—M6 模块代码。
- 未修改 M1/M2/M3/M4/M5/M6 代码、`shared/**`、`e2e/**`、迁移或 owner 表。
- 未修改 `server/build.gradle.kts` / `settings.gradle.kts`。
