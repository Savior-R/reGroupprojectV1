# Course Owner — M2 课程操作与管理 启动提示词

> 模板来源：`docs/team/prompts/template.md`　基线：`main`（开始前 `git fetch origin`，以最新 main 为准）　日期：2026-11-07

你是 **Course/Question Owner** 中负责 **M2 课程操作与管理** 的实现者。先完整阅读本提示词、必读文档和仓库规则，再开始编辑。

## 1. 仓库与分支

- 仓库：`/home/forever_alone/project_school_temp`（GitHub `Savior-R/reGroupprojectV1`）。
- 基线：`main`；开始前 `git fetch origin`，以最新 main 为准。
- 从 `main` 新建 `feat/m2-course`。
- 不得清理 / 提交他人未提交文件；提交使用 `git commit --only <显式路径>`。
- 不得在 main 直接开发，不得 force push。

## 2. 必读文档

1. `CLAUDE.md`、`docs/team/01-模块归属与修改规则.md`。
2. `docs/requirements/M2-课程操作与管理.md`：FR-M2-01—09。
3. `docs/design/03-M1-M2-账号组织课程设计.md` **V0.5** M2 章节。
4. `docs/design/02-API与实时协议.md` **V1.7** M2 接口；`docs/design/01-数据模型与迁移.md` **V1.2**、`docs/design/12-数据库设计.md` **V0.2** 的 M2 表；`docs/design/10-测试与验收.md` **V0.5** M2-T01—09。
5. `docs/implementation/02-M2-实现设计.md`。
6. `docs/team/02-进度看板.md` M2 行。
7. 上游 M1 契约：账号、学校、专业、班级；M3 只读课程 / 任务边界。

## 3. 允许编辑路径

```text
server/src/main/java/com/shijiangtai/m2course/**
server/src/test/java/com/shijiangtai/m2course/**
server/src/main/resources/db/migration/V2*__m2_*.sql
frontend/src/views/course/**
frontend/src/views/task/**（仅课程任务入口相关，不改 M3 页面逻辑）
docs/team/evidence/*m2*
```

不得编辑 M1 / M3 / M4 / M5 / M6 / M7、`shared/**`、`docs/team/01`、owner 表、共享 API 契约。需要改 M1 查询接口或共享 `CurrentUser` 时，先向 Foundation Owner 说明并等待确认。

## 4. 当前状态与依赖

- 本模块 gate：G1 提交，G2 待复审；尚无 P0 实现。
- 上游 M1：账号 / 学校 / 专业 / 班级。M1 可并行开发；M2 先按 `docs/design/02` 的账号查询契约编码，使用 M1 端口 / fake，不等待 M1 完成。
- 下游：M3 读取课程任务；M4 / M7 读取提交与统计。
- 待冻结契约：课程 / 老师组 / 成员 API、课程状态矩阵、M2 表、跨校 403。
- **并行开发约定**：G2 评审可同时进行；M2 不等待其他模块完成，按文档契约 + 端口 / fake 开发；发现契约冲突时停下确认。

## 5. 目标与范围

### 5.1 P0 目标

- FR-M2-01 创建课程：教师建课、课程名校内唯一、专业范围校验。
- FR-M2-02 课程专业范围：创建后不可改。
- FR-M2-03 老师组：平权、增删、至少一名。
- FR-M2-04—06 学生检索 / 加入 / 退出、教师按班级 / 账号加人、重复加入 409、重新加入新行。
- FR-M2-07 课程成员管理。
- FR-M2-08 管理员编辑、归档 / 恢复、成员管理；管理员不能创建课程。
- FR-M2-09 权限与可见性：学生专业匹配、教师全校只读 / 本组管理、管理员本校、跨校 403。
- 课程 `end_at` 与归档状态矩阵。
- 前端课程列表 / 详情 / 管理 / 成员页。

### 5.2 不做

- M3 课堂 / 录制 / 回放、M4 评测、M5 题库、M6 训练、M7 评分 / 统计。
- 账号注册登录、学校 / 专业 / 班级维护（M1）。
- 课程物理删除、管理员创建课程、跨校课程 / 账号。
- 完整 UT / IT / 契约 / E2E 测试（本轮先实现 + smoke，测试后续补齐，见 §7）。

### 5.3 必须遵守的契约

- 课程名称校内唯一；课程学校 / 专业创建后不可改；老师组至少一名；归档只读、学生隐藏。
- 课程成员按 `course_students` 关系保留历史；转班 / 退课不删除历史统计依据。
- 教师检索全校只读、本组管理；管理员可管不可建；跨校 403。
- `end_at` 是教学结束，归档是管理动作；两者都不物理删除课程。
- 任何 API / 表 / 契约变更先改 `02` / `12` / 设计文档并让架构 owner 确认。

## 6. 执行顺序（可并行）

1. 读文档、列任务；如 G2 尚未冻结，先按文档契约编码，不等待。
2. M2 表 / 领域 / JPA / 迁移 `V2*`。
3. 创建课程 / 专业范围 / 老师组。
4. 学生检索 / 加入 / 退出 / 批量加人 / 成员管理。
5. 管理员编辑 / 归档 / 恢复 / 状态矩阵 / 权限。
6. 前端课程页面。
7. 模块 smoke + 自测；完整测试后置。

## 7. 测试策略（本阶段后置）

- 本阶段先保证：`./gradlew compileJava compileTestJava` 或 `docker compose --profile test run --rm test gradle compileJava compileTestJava` 通过；受影响接口做手工 / 模块 smoke。
- 完整 UT / IT / 契约 / E2E 由 Course Owner / QA 在后续测试阶段补齐，不阻塞本阶段并行开发。
- 必测清单（后续）：M2-T01—T09，尤其并发发布 / 归档、跨校 403、答案要点 / 权重不泄漏。
- 不得伪造测试结果；本阶段未跑全量要在汇报中明确写“测试后置”。

## 8. 交付物与汇报格式

1. 分支、commit、变更文件；
2. Owner、是否跨模块、已获谁确认；
3. 实现内容与关键设计决定；
4. 本阶段已验证的编译 / smoke 命令与结果；
5. 未完成测试项、证据计划；
6. 是否合并 / 推送（默认等用户选择）。

## 9. 反模式

- 不得直接改 M1 账号表或 M3 任务表来满足 M2。
- 不得删除 / 跳过他人测试；不得把答案要点 / 权重返回学生。
- 不得在课程归档后仍允许加入 / 加人 / 新建任务。
- 不得把“测试后置”当成“可以不做测试”或伪造结果。
