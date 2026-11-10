# M5 题库 / M6 训练模式 P0 实现证据（2026-11-10）

任务来源：`docs/team/prompts/04-media-m5-m6.md`（Media/Training Owner，M5 + M6 P0）。
说明：任务书把 M5 / M6 一并指派给 Media/Training Owner；看板 M5 行 Owner 为 Course/Question Owner，归属与 owner 表一致性待相关 owner 确认。本次仅新增 / 修改任务书第 3 节允许路径内的文件。

## 1. 实现清单

### 1.1 M5 结构化面试题库（后端）

路径：`server/src/main/java/com/shijiangtai/m5question/**`

- 领域：`Library`、`Category`、`Question`、`QuestionStatus`、`SeedCatalog`、仓储接口与视图。
- 停用语义：题目 / 类别通过 `PATCH` 停用，不提供硬删除接口，避免破坏 M3 已发布任务快照的可追溯性。
- 应用：`QuestionBankApplicationServiceImpl`（学校题库懒初始化、类别 / 题目管理、跨校 403、名称唯一）、`QuestionBankQueryServiceImpl`（列表 / 搜索仅启用题目；M3 选题查询）、`SeedServiceImpl`、`LibraryInitializer`、`M5Access`。
- 接口：
  - `GET/POST/PATCH /api/v1/question-bank/categories`
  - `GET/POST/PATCH /api/v1/question-bank/questions`（`category_id`、`status`、`keyword`、`page`、`size`）
  - `POST /api/v1/admin/question-bank/demo-init`（仅 demo profile，生产 404）
- 持久化：`library` / `question_categories` / `questions` JPA 适配器；迁移 `V3__m5_question.sql`。
- 种子：classpath `seed/question-bank.json`；建校复制种子数据；懒初始化幂等；并发唯一冲突回退。
- M3 契约：`QuestionBankPublishAdapter` 只向 M3 发布流程返回题目（停用题目 `enabled=false` 由 M3 `TaskPublishPolicy` 拒绝）；学生访问返回 `ROLE_NOT_ALLOWED`；答案要点不进入学生视图。

### 1.2 M6 训练模式（后端）

路径：`server/src/main/java/com/shijiangtai/m6training/**`

- 领域：模板、会话元数据、记录、参考材料、虚拟学生、配置快照 / 有效配置、状态与错误码。
- 应用：
  - `TrainingTemplateServiceImpl`：从已发布 / 已关闭自由任务发布模板；目标课程校验；创建者下架；重复校验。
  - `TrainingTemplateReconciler`：每 5 分钟兜底，课程不存在 / 未激活 / 已结束 / 创建者离开教师组时自动下架。
  - `TrainingSessionServiceImpl`：模板 / 自由训练创建；school / 课程 / 学生 / 材料校验；`session_type=training`、`task_id=NULL`；结束 / 取消。
  - `TrainingRecordServiceImpl`：选中（仅本人、已结束或 reviewing、三路录制完整）、PostgreSQL advisory lock 串行化、每组最多 3 条、第 4 条删最旧、1 个月过期、评测事件、报告仅本人。
  - 清理：`TrainingRecordCleanupListener`（事务提交后）、`TrainingRecordCleanupExecutor`（先 MinIO 录制 / 帧 / 材料对象，再删 M4 行与 M3 会话）、`TrainingCleanupServiceImpl`。
  - 调度：`TrainingMaintenanceScheduler`（`@Profile("!test")`，自动结束 1 分钟、模板对账 5 分钟、清理 10 分钟）。
- 接口：
  - `GET /api/v1/student/training/templates`、`GET /api/v1/student/training/templates/{templateId}`
  - `GET /api/v1/training/templates`、`POST /api/v1/training/templates`、`POST /api/v1/training/templates/{templateId}/unpublish`
  - `POST /api/v1/training/sessions`、`GET /{sessionId}`、`POST /{sessionId}/end`、`POST /{sessionId}/cancel`
  - `POST/DELETE /api/v1/training/materials`
  - `POST/GET /api/v1/training/records`、`GET/DELETE /api/v1/training/records/{recordId}`、`GET /{recordId}/evaluation`
- 持久化：迁移 `V7__m6_training.sql`；训练模板 / 会话元数据 / 记录 / 材料 JPA 适配器；会话与录制复用 M3 表，训练会话 `task_id` 可空。
- 跨模块：
  - `SourceTaskJdbcAdapter` 读取 M3 任务快照并把题干 / 答案要点 / 时长 / 权重 / 虚拟学生复制进模板快照。
  - `CourseDirectoryJdbcAdapter` 校验目标课程与教师归属。
  - `TrainingClassroomJdbcAdapter` 创建 / 更新 / 删除 `session_type=training` 的 M3 会话，不生成 Submission。
  - `TrainingEvaluationJdbcAdapter` 读取 / 删除 M4 训练评测表，发布 `TrainingRecordSelectedEvent`。

### 1.3 前端

- `frontend/src/views/question/**`：`QuestionBankPage.vue`（类别 / 题目 CRUD、启停、分页、筛选）、`api.ts`、`routes.ts`。
- `frontend/src/views/training/**`：教师模板页、训练首页、模板 / 自由训练配置、训练房间、记录列表 / 详情、回放页、虚拟学生编辑器、API / 类型 / 路由。
- 路由以 `questionRoutes` / `trainingRoutes` 数组导出；`frontend/src/app/router.ts` 是共享文件，本次未修改，需由路由 owner 合并。

## 2. 关键契约确认

| 项 | 实现 |
|---|---|
| M5 题库隔离 | 按 `school_id`；跨校访问 403，不存在 404 |
| 答案要点 | 创建 / 更新非空；学生接口不返回 |
| M3 选题 | 只返回启用题目；发布时复制题干与答案要点到任务快照 |
| M6 模板来源 | 仅已发布 / 已关闭自由任务；快照版本随模板保存 |
| 训练会话 | `session_type=training`、`task_id=NULL`，不生成 Submission，M7 不可见 |
| 记录上限 | 每模板 3 条；自由训练按 `template_id=NULL` 分组 3 条；advisory lock 防并发 |
| 过期 / 清理 | 选中后 1 个月；先对象存储后数据库；下架 / 退课不删除已选中记录 |
| 报告权限 | 仅学生本人；教师 / 管理员 403 |

## 3. 验证结果

环境：Windows 11，JDK 21（`C:\Users\wsc20\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`），Gradle 9.4.1。

### 3.1 后端编译

```bash
cd server
JAVA_HOME='C:\Users\wsc20\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2' \
  ~/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle \
  compileJava compileTestJava --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 3.2 模块 smoke（最小单元冒烟，非全量）

```bash
... gradle test \
  --tests 'com.shijiangtai.m5question.application.M5ModuleSmokeTest' \
  --tests 'com.shijiangtai.m6training.application.M6ModuleSmokeTest'
```

结果：`BUILD SUCCESSFUL`；测试 XML：

| 测试类 | tests | skipped | failures | errors |
|---|---:|---:|---:|---:|
| `M5ModuleSmokeTest` | 4 | 0 | 0 | 0 |
| `M6ModuleSmokeTest` | 7 | 0 | 0 | 0 |

覆盖点：

- M5：种子目录加载（3 类 / 6 题，答案要点均非空，缓存同实例）；空 / null 答案要点拒绝；创建去空格并默认启用；学生 403、教师允许。
- M6：M3 快照克隆（主题 / 版本 / 课程 / 时长 / 权重和 100 / 虚拟学生）；模拟考核时长固定为上限；非法快照拒绝；模板会话保留时长 / 权重 / 主题并可替换虚拟学生；自由训练默认值（进阶 900/1200、模拟 1200/1200、3 名默认学生）；主题超长拒绝；学生 / 教师角色门禁。

### 3.3 前端类型检查与构建

```bash
cd frontend
npm ci --no-audit --no-fund
npm run build
```

结果：`vue-tsc -b && vite build` 通过，`✓ built in 551ms`；仅存在既有 chunk > 500 kB 警告。新增页面已被类型检查，但因共享路由未合并，不进入当前构建入口。

### 3.4 未能执行的验证

- 无 Docker CLI、无本地 PostgreSQL / psql，无法启动 Compose 与真实数据库，因此未执行 HTTP 集成冒烟、Flyway 实库迁移验证、MinIO 清理验证、WebSocket / 回放验收。
- 前端未做浏览器端点击验收。

## 4. 测试后置

按任务书第 96 行，完整测试后续补齐；本次**未伪造**任何全量测试结果。

| 用例 | 内容 | 层级 | 状态 |
|---|---|---|---|
| M5-T01 | 建校复制种子题库；懒初始化幂等 | 集成 | 待执行 |
| M5-T02 | 类别 / 题目 CRUD；名称唯一；跨校 403 | 集成 | 待执行 |
| M5-T03 | 题目停用 / 删除；答案要点非空校验 | 集成 | 待执行 |
| M5-T04 | 空题库学校不能创建面试任务 | 集成 | 待执行 |
| M5-T05 | M3 选题只读启用题目；发布快照 | 集成 | 待执行 |
| M5-T06 | 发布后 M5 编辑 / 停用不影响快照 | 集成 | 待执行 |
| M5-T07 | 学生访问题库 403；答案要点不返回学生 | 安全 | 待执行 |
| M5-T08 | demo 初始化接口 / CLI；种子 `created_by=NULL`；生产 profile 404 | 集成 | 待执行 |
| M6-T01 | 从已发布 / 已关闭自由任务发布模板；非法来源拒绝 | 集成 | 待执行 |
| M6-T02 | 目标课程可见性；跨课程；下架 / 自动下架 | 集成 | 待执行 |
| M6-T03 | 学生配置虚拟学生；不能改时长 / 权重 | 集成 | 待执行 |
| M6-T04 | 进阶 / 模拟考核时长；到点自动结束 | 集成 | 待执行 |
| M6-T05 | 自由训练默认；参考材料可选；解析失败 | 集成 | 待执行 |
| M6-T06 | session 状态机；不生成 Submission；M7 不可见 | 集成 | 待执行 |
| M6-T07 | 选中生成记录；每模板 3 条；自由训练分组 3 条；第 4 条删最旧 | 集成 | 待执行 |
| M6-T08 | 记录 1 个月 / 删除 / 过期；读路径校验 `expires_at`；清理 MinIO + DB | 集成 | 待执行 |
| M6-T09 | 报告仅本人；教师 / 管理员 403 | 安全 | 待执行 |
| M6-T10 | 模板下架 / 退课后已选中记录保留 | 集成 | 待执行 |
| M6-T11 | 模板下架 / 退课后：preparing 取消、in_progress 自然结束、ended/reviewing 选中规则 | 集成 | 待执行 |

测试计划：先启动 Compose（PostgreSQL + MinIO + app），用真实 Flyway 迁移验证 `V3` / `V7`；再按上表补集成测试，重点覆盖种子幂等、快照不受题库编辑影响、训练不生成 Submission、M7 不可见、第 4 条删最旧、1 个月过期与对象 / DB 清理顺序；最后补前端 Vitest 与浏览器验收。

## 5. 遗留跨模块事项（未修改他人模块）

1. **M3 通用会话 / 回放**：`SessionRepositoryAdapter.toDomain` 在 `task_id` 为 NULL 时抛异常，训练会话无法通过 M3 通用查询 / 回放读取；需 M3 Owner 支持 `session_type=training` 或允许空 `task_id`。
2. **M4 评测**：`TrainingRecordSelectedEvent` 当前无消费者，M4 训练评测实现 / 监听器缺失，报告会保持 pending；需 M4 Owner 接入。
3. **共享路由**：`frontend/src/app/router.ts` 未合并 `questionRoutes` / `trainingRoutes`，页面暂不可达。
4. **M1 建校钩子**：建校时复制 M5 种子题库的跨模块调用未接入；当前依赖懒初始化兜底。
5. **M5 owner**：看板 M5 行 Owner 为 Course/Question Owner，与任务书指派不一致，需 owner 表确认。

## 6. 本地测试网页（Mock）

因本机无 Docker / PostgreSQL，后端无法启动，另提供仅在 Vite 开发服务器可访问的离线预览页：

- `frontend/src/views/question/preview.html`
- `frontend/src/views/question/preview.ts`
- `frontend/src/views/question/PreviewPage.vue`
- `frontend/src/views/question/mockApi.ts`（拦截共享 Axios 客户端的 `get/post/patch/delete`，数据仅存内存）

启动：

```bash
cd frontend
npm run dev -- --host 127.0.0.1 --port 5173
```

访问：`http://127.0.0.1:5173/src/views/question/preview.html`

页面包含 M5 题库管理与 M6 教师模板两个标签页，可演示筛选、分页、新增 / 编辑题目、启停、发布 / 下架模板。Mock 数据不会落库，不代表后端联调结果；生产入口 `src/main.ts` 不引用这些文件，`npm run build` 仍通过。

验证：`npm run build` 通过（`✓ built in 885ms`）；Edge headless 渲染 `preview.html` 成功，DOM 包含“M5 题库管理”“M6 训练模板”“教学设计”“荷塘月色”等页面内容。

## 7. 变更文件归属

- M5：`server/src/main/java/com/shijiangtai/m5question/**`、`server/src/test/java/com/shijiangtai/m5question/**`、`V3__m5_question.sql`、`frontend/src/views/question/**`。
- M6：`server/src/main/java/com/shijiangtai/m6training/**`、`server/src/test/java/com/shijiangtai/m6training/**`、`V7__m6_training.sql`、`frontend/src/views/training/**`。
- 本证据文件：`docs/team/evidence/2026-11-10-m5-m6-question-training.md`。
- 未修改 `docs/team/01`、owner 表、M1–M4 / M7 / `shared/**` 实现、M3 课堂 / 录制实现、共享路由。
