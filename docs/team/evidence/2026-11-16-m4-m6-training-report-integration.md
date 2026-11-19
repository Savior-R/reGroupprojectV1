# 2026-11-16 全项目整合修复（M4/M6 报告、共享路由、JWT、demo）

> 授权：用户于 2026-11-16 明确作为全项目整合负责人，拥有修改各模块的权利；本记录据此跨 M1 / M2 / M4 / M5 / M6 / M7 / 共享前端层执行。
> 环境：Docker Desktop 29.6.2 / Docker Engine 29.6.2 / Compose v5.3.1 / PostgreSQL 18 / Spring Boot 4.1.2 / Vite 8.2.2 / Node 22.22.1。

## 1. M4 / M6 训练评测报告接口冲突

后端启动时 Spring 报 `Ambiguous mapping`，M4 与 M6 注册了完全相同的接口：

| 模块 | 文件 | 原实现 |
|---|---|---|
| M4 | `server/src/main/java/com/shijiangtai/m4eval/api/TrainingEvaluationController.java` | `GET /api/v1/training/records/{recordId}/evaluation` → `EvaluationView`（评测状态） |
| M6 | `server/src/main/java/com/shijiangtai/m6training/api/TrainingRecordController.java` | 同一路径 → `TrainingReportView`（训练报告） |

两个前端也按不同结构调用同一路径：`frontend/src/views/report/api.ts` 期望 `EvaluationView`，`frontend/src/views/training/api.ts` 期望 `TrainingReport`。

按设计文档统一为 M4 提供、M6 复用（02 §4.4、06 §9.1、08 §10.6）：

1. M4 `TrainingEvaluationController` 返回 `ReportView`，由 `EvaluationReportQueryService.reportByTrainingRecord` 组装报告。
2. M4 新增训练记录有效性校验：记录必须为 `selected / evaluated` 且未过期，否则 404，保持 M6 原语义。
3. M6 删除重复的 `evaluation` 映射、`TrainingReportView`、`TrainingRecordService.getEvaluation`、`TrainingEvaluationPort.findReport` 及 JDBC 报告读取。
4. M6 前端 `getTrainingReport` 调用 M4 接口，并在 M6 边界把 `ReportView` + `/issues` 适配为页面现有 `TrainingReport` 结构。

## 2. 共享路由挂载

`frontend/src/app/router.ts` 之前没有合并 M4 / M5 / M6 的路由，页面在浏览器中不可达。本次补上：

- M4：`/evaluations/:evaluationId/report`、`/submissions/:submissionId/report`、`/training/records/:recordId/report`。
- M5：展开 `questionRoutes` → `/teacher/question-bank`。
- M6：展开 `trainingRoutes` → `/student/training/**`、`/teacher/training/templates`。
- M7 既有路由补 `requiresAuth` / `studentOnly` 守卫。
- `AdminShell.vue` 学生导航新增「训练中心」。

## 3. 身份来源收敛

修复前 M3/M4/M6/M7 的 `HeaderCurrentUserProvider` 直接信任 `X-Account-*` 请求头，JWT 只用于通过 Spring Security；直连 8090 可绕过 Nginx 伪造身份。

- 新增 `M1CurrentUserProvider`（`@Primary`），从 JWT / `M1PrincipalProvider` 解析 `CurrentUser`；管理员未建校时明确 403。
- `HeaderCurrentUserProvider` 加 `@Profile({"test", "dev"})`，生产 / docker 不再注册。
- `docker` profile 仅接受 Bearer JWT；`X-Account-*` 只在 dev/test 兜底，测试不需要改。
- 同步修正 `docker-compose.yml`、`server/README.md`、`frontend/README.md` 中关于请求头身份的过期描述。

## 4. M6 自动结束调度 SQL

`TrainingSessionMetaRepositoryAdapter.listInProgressPastDeadline` 把 `Instant` 直接传给 `JdbcTemplate`，PostgreSQL 驱动报 `Can't infer the SQL type to use for an instance of java.time.Instant`。改为 `Timestamp.from(now)` 后调度器不再报错。

## 5. demo 训练评测数据

新增 demo-only 迁移 `server/src/main/resources/db/demo/V901__demo_training_evaluation.sql`：

- 为 `student.a01` 的训练记录 `a0000000-...0011` 插入 completed 评测（84.50 分、5 维、3 条问题/建议）。
- 先删除 M4 对账器可能生成的 failed 占位评测，避免 `(source_type, source_id)` 唯一键冲突。
- 训练记录状态同步为 `evaluated`。

## 6. Docker 构建缓存

`server/Dockerfile` 的 Gradle 构建增加 BuildKit 缓存挂载：

```dockerfile
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle bootJar --no-daemon -x test
```

降低源码变更后重复下载依赖、以及 Maven Central 偶发 TLS 握手失败的概率；冷缓存首次构建仍可能需要 `docker build --network=host`。

## 7. 修改文件

| 文件 | 模块 / 归属 |
|---|---|
| `docs/design/02-API与实时协议.md`、`06`、`08` | 契约 |
| `server/.../m4eval/api/TrainingEvaluationController.java` | M4 |
| `server/.../m4eval/application/EvaluationReportQueryService.java` | M4 |
| `server/.../m4eval/application/port/EvaluationInputQuery.java` | M4 |
| `server/.../m4eval/infrastructure/query/JdbcEvaluationInputQuery.java` | M4 |
| `server/.../m6training/api/TrainingRecordController.java` | M6 |
| `server/.../m6training/application/TrainingRecordService.java` | M6 |
| `server/.../m6training/application/TrainingRecordServiceImpl.java` | M6 |
| `server/.../m6training/application/view/TrainingReportView.java`（删除） | M6 |
| `server/.../m6training/domain/port/TrainingEvaluationPort.java` | M6 |
| `server/.../m6training/infrastructure/crossmodule/TrainingEvaluationJdbcAdapter.java` | M6 |
| `server/.../m6training/infrastructure/persistence/jpa/TrainingSessionMetaRepositoryAdapter.java` | M6 |
| `server/.../m1auth/application/M1CurrentUserProvider.java`（新增） | M1 |
| `server/.../m3classroom/api/security/HeaderCurrentUserProvider.java` | M3 / 共享认证 |
| `server/src/main/resources/db/demo/V901__demo_training_evaluation.sql`（新增） | Demo |
| `server/Dockerfile`、`docker-compose.yml`、`server/README.md`、`frontend/README.md` | 构建 / 文档 |
| `frontend/src/app/router.ts`、`main.ts`、`views/*/routes.ts`、`views/training/api.ts`、`views/report/api.ts`、`views/admin/AdminShell.vue` | 共享前端 / M4 / M5 / M6 |

## 8. 验证

### 8.1 构建与测试

- `npm --prefix frontend run build`：通过（仅既有 chunk > 500 kB 警告）。
- `npm --prefix frontend run test -- --run`：18 files / 143 tests 全部通过。
- `docker build --network=host -t shijiangtai-app:latest ./server`：通过。
- 定向后端测试：`TaskControllerIT`、`StudentTaskControllerIT`、`M6ModuleSmokeTest` 等 5 个类，**29 tests / 0 failures / 0 errors / 0 skipped**，`BUILD SUCCESSFUL`。
- `docker compose up -d --no-build --force-recreate app`：app `healthy`，`/actuator/health` 200 `UP`。

### 8.2 接口冒烟

| 场景 | 结果 |
|---|---|
| Flyway demo 库 | V900 + V901 均 `Success` |
| 学生 `student.a01` 仅 Bearer 访问训练报告 | 200；`evaluation_id=80000000-...0011`、`status=completed`、`total_score=84.5`、5 维、2 条 suggestions |
| `GET /evaluations/80000000-...0011/issues` | 200；3 条问题 / 建议 |
| 教师 `teacher.a1` 仅 Bearer 访问训练报告 | 403 `训练报告仅本人可见` |
| docker profile 仅 `X-Account-*`、无 Bearer | 401 `未认证`（请求头伪造已失效） |
| `GET /api/v1/evaluations/80000000-...0001/report` | 200；completed，86.6 分，5 维 |
| `GET /api/v1/training/records/a000...0011`（M6 记录详情） | 200；`status=evaluated`、`evaluation_status=completed`、`total_score=84.5`、`report_available=true` |
| `GET /api/v1/question-bank/categories`、`/questions`（M5） | 200 |
| `GET /api/v1/tasks/600...001/grading-submissions`、`/stats`、`/courses/400...101/stats`（M7） | 200 |
| `GET /api/v1/courses` | 200 |
| M6 调度器日志 | 重启后无 `auto-end scan failed` / `Can't infer the SQL type` |
| 前端路由 HTTP | `/teacher/question-bank`、`/student/training`、M4 三个报告页均 200；构建产物包含对应页面 chunk |

## 9. 已知问题 / 后续

1. **入口链接不足**：M4 报告页、M5 题库、M6 教师模板页已可路由，但 M7 / 课程页尚未加入口按钮；当前需直接访问 URL。
2. **demo 训练记录有效期**：demo seed 的 `expires_at` 为 2026-12-03，过期后训练报告按契约返回 404；需要重置 demo 库或调整 seed。
3. **demo 多角色**：V14 在 V900 之前回填 `account_roles`，demo 账号没有附加角色；JWT 仍包含主身份角色，但教师兼管理员等多角色场景需单独造数。
4. **未跑全量后端测试 / 浏览器 E2E**：本轮只跑了定向 IT + 冒烟；完整 `gradle test` 与 Playwright E2E 仍待执行。
5. **Maven 冷缓存**：首次无缓存构建仍可能遇到 Maven Central TLS 握手失败；已加 Gradle 缓存挂载，必要时继续使用 `--network=host`。
6. **未知 `/api/**` 路径返回 500**：`GlobalExceptionHandler` 未单独处理 `NoResourceFoundException`，不存在的 API 路径会被当成静态资源缺失并返回 500；建议后续映射为 404。
