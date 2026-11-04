# 2026-11-04 M3A Docker + PostgreSQL 垂直切片证据

> 分支：`feat/m3a-task-config`
> 计划：`docs/superpowers/plans/2026-11-04-m3a-task-configuration.md`（Task 1–14）
> 执行人：M3A Owner（当前使用者）
> 环境：Docker Engine 29.6.2 / Compose v5.3.1 / PostgreSQL 18.6 / Spring Boot 4.1.2 / Java 21 / Flyway 12.4.0
> 结论：M3A 垂直切片可构建、可启动、全量测试通过、Compose smoke 通过。G2 设计冻结仍未关闭，完成度保持 10%。

## 1. 范围与授权

本切片只包含 M3A：任务草稿 / 编辑 / 删除、参考材料解析与 MinIO 存储、虚拟学生、评分权重、结构化面试选题、发布 / 关闭、不可变配置快照、PostgreSQL 持久化、教师 / 学生视图。

不做：M3B 课堂 / WebSocket、M3C 录制 / 回放、前端、真实 M1 JWT、M2 / M5 正式实现、E2E、NFR。

跨模块文件与授权：

| 文件 / 目录 | Owner | 授权 |
|---|---|---|
| `server/Dockerfile`、`server/.dockerignore`、根 `docker-compose.yml`、`server/docker/postgres/init/**` | Foundation / Docker 环境 | 用户 2026-11-04 明确授权（M3A 计划 Global Constraints） |
| `server/build.gradle.kts`：新增 `org.springframework.boot:spring-boot-flyway` | Foundation | 用户 2026-11-04 本次会话明确批准；原因见 §4 |
| `server/src/main/resources/application-docker.yml`：Flyway locations 增加 `classpath:db/dev` | Foundation（模块配置片段） | M3A 计划 Task 1 已授权创建 / 维护 Docker profile 配置 |
| `server/src/main/resources/db/dev/V950__dev_upstream.sql` | M3A 开发数据（dev-only） | 仅 docker profile 加载；不进入 test / demo / prod |

> 以上授权仅对列出的文件有效，不推广到其他共享路径或其他模块。

## 2. 全量测试

命令：

```bash
docker compose --profile test run --rm test \
  gradle clean test --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 46s`，**26 个测试类 / 156 条用例，0 failure / 0 error / 0 skipped**。

| 层级 | 测试类（用例数） | 小计 |
|---|---|---:|
| 上下文 / 架构 | `ServerApplicationTests`(1)、`ArchitectureTest`(3)、`FlywayAutoConfigurationIT`(1) | 5 |
| 领域 UT | `RubricWeightsTest`(6)、`VirtualStudentConfigTest`(6)、`ScenarioConfigTest`(5)、`QuestionConfigTest`(5)、`ReferenceMaterialTest`(5)、`TaskDraftTest`(10)、`TaskTest`(11)、`TaskSnapshotFactoryTest`(6)、`TaskPublishPolicyTest`(15) | 69 |
| 应用 UT | `TaskAccessPolicyTest`(11)、`TaskApplicationServiceTest`(10)、`TaskQueryServiceTest`(5)、`TaskPublishServiceTest`(8)、`TaskMaterialServiceTest`(7) | 41 |
| API / 解析 UT | `TaskRequestMapperTest`(3)、`GlobalExceptionHandlerTest`(2)、`MaterialTextExtractorTest`(6) | 11 |
| 持久化 IT | `TaskRepositoryIT`(3)、`TaskJsonCodecIT`(3)、`TaskPublishPersistenceIT`(5) | 11 |
| API IT | `TaskControllerIT`(12)、`StudentTaskControllerIT`(3)、`TaskMaterialControllerIT`(4) | 19 |
| **合计** | | **156** |

测试环境说明：

- 集成测试使用 Compose 的 `postgres` 服务（`shijiangtai_test`），不使用 Testcontainers；这是计划中的明确环境选择。
- 测试库由 Flyway `db/migration` 迁移；`@Sql` 在每条集成用例前执行 `test-upstream.sql` + `test-clean.sql`。
- 并发发布用例原先假设“先提交的 Future 一定赢”，线程调度变化时会误报失败；已改为与顺序无关的断言：恰好一个 `published` + 一个 `TASK_ALREADY_PUBLISHED`，分配名单恰好 2 条、事件恰好 1 条。该修改只涉及测试断言，不改变生产逻辑。

## 3. Compose smoke

命令：

```bash
docker compose up -d --build
curl -fsS http://localhost:8080/actuator/health
```

结果：

- `app` 健康：`{"groups":["liveness","readiness"],"status":"UP"}`。
- 启动日志：`Successfully applied 13 migrations to schema "public", now at version v950`（`shijiangtai` 库，含 docker-only 的 `V950__dev_upstream.sql`）。
- `postgres`、`redis`、`minio` 均为 healthy。

Smoke 流程（教师固定头 `X-Account-Id=...0010`、`X-School-Id=...0001`、`X-Account-Type=TEACHER`）：

| 步骤 | 请求 | 结果 |
|---|---|---|
| 1. 创建 free 任务 | `POST /api/v1/courses/...0100/tasks` | HTTP 200；`task_id=e113da20-d596-4c1d-b99c-e60381ecc94f`；权重 `30/20/20/15/15`；1 个虚拟学生 |
| 2. 上传参考材料 | `POST /api/v1/tasks/{id}/material`（`教案.txt`，147 B） | HTTP 200；`parse_status=parsed`，`char_count=49`，`parsed_text=null`（上传响应按设计只返回 preview） |
| 3. 发布 | `POST /api/v1/tasks/{id}/publish` | HTTP 200；`status=published`，`config_version=1`，`assigned_count=2` |
| 4. 学生列表 | `GET /api/v1/student/tasks`（学生 ...0020） | HTTP 200；返回该任务，`assignment_status=assigned`；无 `rubric_weights` / `reference_material` / `published_questions` |
| 5. 学生详情 | `GET /api/v1/student/tasks/{id}` | HTTP 200；同样不泄露权重、材料、答案要点、虚拟学生参数 / 性格 |

MinIO 对象校验：

```bash
docker compose exec minio mc alias set local http://127.0.0.1:9000 minioadmin minioadmin
docker compose exec minio mc ls --recursive local/shijiangtai/materials
```

输出：`materials/00000000-...-0001/00000000-...-0100/e113da20-.../04cc184a-....txt`，147 B。

数据库校验：

```sql
select id, status, config_version, published_at is not null from m3_classroom.tasks;
-- published | 1 | t
select task_id, count(*) from m3_classroom.task_assignments group by task_id;
-- 2
```

## 4. 本次发现并修复的问题

### 4.1 Spring Boot 4 Flyway 自动配置缺失（生产构建缺陷）

- 现象：`docker compose up` 后应用健康，但 `shijiangtai` 库没有任何业务表；创建任务返回 500，日志为 `relation "m3_classroom.tasks" does not exist`。
- 根因：Spring Boot 4 将 Flyway 自动配置拆分为独立模块 `spring-boot-flyway`。原构建只声明了 `flyway-core`，应用启动时 Flyway 从未执行；此前集成测试全绿是因为 `shijiangtai_test` 是由外部 `flyway` Compose 服务预先迁移的，掩盖了该缺陷。
- 修复：`server/build.gradle.kts` 增加 `implementation("org.springframework.boot:spring-boot-flyway")`。保留 `flyway-core` 与 `flyway-database-postgresql`。
- 回归测试：新增 `FlywayAutoConfigurationIT`，断言 `Flyway` bean 存在、已应用迁移非空、无 pending。修复前该测试因 `NoSuchBeanDefinitionException` 失败，修复后通过。
- 影响：所有 profile 启动时按既有 `application.yml` 配置执行 `migrate`；无 API / 表 / 事件契约变化。

### 4.2 并发发布测试顺序敏感（测试缺陷）

`TaskPublishServiceTest` / `TaskPublishPersistenceIT` 原先硬编码第一个 Future 获胜。已改为收集两个 Future 的结果，断言恰好一个成功、一个 `TASK_ALREADY_PUBLISHED`，与线程调度顺序无关。

## 5. 覆盖与未覆盖

已覆盖（对应 `10` §6.4）：

- M3-T01 三种模式创建、模式切换清空不兼容配置
- M3-T02 时长 / 权重 / 虚拟学生边界
- M3-T03 PDF / DOCX / TXT / MD 解析；无文本 PDF 失败
- M3-T04 材料替换 / 删除 / 草稿删除清理对象存储
- M3-T05 结构化面试 1 / 10 / 11 题、重复 / 停用 / 无答案要点
- M3-T06 发布事务、分配名单、0 学生回滚、并发发布恰好一个成功
- M3-T07 快照不可变、关闭任务、发布后只读
- M3-T08 学生可见性：不返回参考材料 / 权重 / 答案要点 / 虚拟学生参数
- 架构规则：domain 无 Spring / Jackson / JPA 依赖；application 不依赖 api / infrastructure；api 不依赖 infrastructure
- JSONB 往返、发布失败回滚、after-commit 事件、Flyway 启动迁移

未覆盖 / 后续：

- M1 JWT 与 URL 级权限（当前 `X-Account-*` 为 dev-only）
- M2 / M5 正式实现（端口 + 内存适配器）
- M3B 课堂 / WS / 面试流程、M3C 录制 / 回放
- 前端、E2E、性能 / 安全 / 迁移验收
- M1 / M2 正式上游数据；`V950__dev_upstream.sql` 只服务 docker profile
